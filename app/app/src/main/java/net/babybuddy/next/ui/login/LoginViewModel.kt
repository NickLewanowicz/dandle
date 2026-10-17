package net.babybuddy.next.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import net.babybuddy.next.data.auth.LoginError
import net.babybuddy.next.data.auth.LoginFlow
import net.babybuddy.next.data.auth.LoginResult
import net.babybuddy.next.data.prefs.SessionStore
import net.babybuddy.next.domain.NormalizedServerUrl
import net.babybuddy.next.domain.normalizeServerUrl

/** Which error banner to show; null = none. Copy lives in the screen (spec §1.1.5). */
enum class LoginBanner {
    UNREACHABLE,
    BAD_CREDENTIALS,
    NO_CHILDREN,
    HTTP_ERROR,
    SESSION_EXPIRED, // neutral info banner, not an error (UX spec §1 states table)
}

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val submitting: Boolean = false,
    val banner: LoginBanner? = null,
    val httpStatus: Int = 0,
    /** Shown when the banner is SESSION_EXPIRED: fields arrive pre-filled from the session. */
    val sessionPrefill: Boolean = false,
    val cleartextDialogVisible: Boolean = false,
) {
    /** Live normalization hint — shown only while it differs from the typed text (UX spec §1). */
    val normalizedServer: NormalizedServerUrl?
        get() = if (serverUrl.isBlank()) null else normalizeServerUrl(serverUrl)

    val showNormalizationHint: Boolean
        get() = normalizedServer != null && normalizedServer?.url != serverUrl.trim()

    val isCleartext: Boolean get() = normalizedServer?.isCleartext == true

    val canSubmit: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !submitting
}

sealed interface LoginEvent {
    data object SignedIn : LoginEvent
}

class LoginViewModel(
    private val loginFlow: LoginFlow,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<LoginEvent>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<LoginEvent> = _events.asSharedFlow()

    /** Set once the user confirms the cleartext dialog; reset on cancel. */
    private var cleartextAcknowledged = false

    init {
        // Pre-fill stored server URL + username (always; mandatory for the
        // session-expired path per UX spec §1, matches the original app otherwise).
        viewModelScope.launch {
            val session = sessionStore.sessionFlow.firstOrNull()
            if (session != null) {
                _uiState.update {
                    it.copy(
                        serverUrl = it.serverUrl.ifBlank { session.serverUrl.orEmpty() },
                        username = it.username.ifBlank { session.username.orEmpty() },
                        banner = if (session.sessionExpired) LoginBanner.SESSION_EXPIRED else it.banner,
                        sessionPrefill = session.sessionExpired,
                    )
                }
            }
        }
        // The ViewModel is store-backed and outlives single login attempts (see AppNavHost), so
        // the session-expired notice must be reactive: a 401 pause during sync (spec §6.3.6)
        // can arrive long after this instance was created, while the user is elsewhere.
        viewModelScope.launch {
            sessionStore.sessionFlow.collect { session ->
                if (session.sessionExpired && _uiState.value.banner == null) {
                    _uiState.update {
                        it.copy(banner = LoginBanner.SESSION_EXPIRED, sessionPrefill = true)
                    }
                }
            }
        }
    }

    fun onServerUrlChange(value: String) = _uiState.update {
        it.copy(serverUrl = value, banner = null, sessionPrefill = false)
    }

    fun onUsernameChange(value: String) = _uiState.update {
        it.copy(username = value, banner = null, sessionPrefill = false)
    }

    fun onPasswordChange(value: String) = _uiState.update {
        it.copy(password = value, banner = null, sessionPrefill = false)
    }

    fun onPasswordVisibilityToggle() = _uiState.update {
        it.copy(passwordVisible = !it.passwordVisible)
    }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        if (state.isCleartext && !cleartextAcknowledged) {
            _uiState.update { it.copy(cleartextDialogVisible = true) }
            return
        }
        doLogin()
    }

    fun onCleartextContinue() {
        cleartextAcknowledged = true
        _uiState.update { it.copy(cleartextDialogVisible = false) }
        doLogin()
    }

    fun onCleartextCancel() {
        cleartextAcknowledged = false
        _uiState.update { it.copy(cleartextDialogVisible = false) }
    }

    private fun doLogin() {
        val state = _uiState.value
        _uiState.update { it.copy(submitting = true, banner = null) }
        viewModelScope.launch {
            val result = loginFlow.login(
                rawUrl = state.serverUrl,
                username = state.username,
                password = state.password,
            )
            when (result) {
                is LoginResult.Success -> {
                    _uiState.update { it.copy(submitting = false, banner = null) }
                    _events.tryEmit(LoginEvent.SignedIn)
                }
                is LoginResult.Failure -> {
                    val status = (result.error as? LoginError.HttpError)?.status ?: 0
                    val banner = when (result.error) {
                        LoginError.Unreachable -> LoginBanner.UNREACHABLE
                        LoginError.BadCredentials -> LoginBanner.BAD_CREDENTIALS
                        LoginError.NoChildren -> LoginBanner.NO_CHILDREN
                        is LoginError.HttpError -> LoginBanner.HTTP_ERROR
                    }
                    _uiState.update {
                        it.copy(submitting = false, banner = banner, httpStatus = status)
                    }
                }
            }
        }
    }
}
