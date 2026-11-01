package app.dandle.ui.login

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
import app.dandle.data.auth.LoginError
import app.dandle.data.auth.LoginFlow
import app.dandle.data.auth.UrlVerifyResult
import app.dandle.data.auth.LoginResult
import app.dandle.data.backend.Backend
import app.dandle.data.cloud.CloudAuth
import app.dandle.data.cloud.CloudError
import app.dandle.data.cloud.CloudLoginResult
import app.dandle.data.prefs.SessionStore
import app.dandle.domain.NormalizedServerUrl
import app.dandle.domain.normalizeServerUrl

/** Which error banner to show; null = none. Copy lives in the screen (spec §1.1.5). */
enum class LoginBanner {
    UNREACHABLE,
    BAD_CREDENTIALS,
    NO_CHILDREN,
    HTTP_ERROR,
    SESSION_EXPIRED, // neutral info banner, not an error (UX spec §1 states table)
    USERNAME_TAKEN,  // cloud create: name already registered
    WRONG_PASSPHRASE, // cloud: envelope unwrap failed — points at the passphrase field
}

/**
 * Login flow modes (brief 04): the landing asks where the data lives; each mode opens its
 * own sub-flow. SERVER preserves the pinned two-step progressive disclosure byte-for-byte.
 */
enum class LoginMode { DEVICE, SERVER, CLOUD }

/** Progressive disclosure steps. MODE is the landing; the rest are sub-flow screens. */
enum class LoginStep { MODE, DEVICE_CHILD, URL, CREDENTIALS, CLOUD_ENTRY }

/** Cloud sub-flow tabs (brief 04 §B): shared fields, create adds the confirm row. */
enum class CloudTab { SIGN_IN, CREATE_ACCOUNT }

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
    /** T8: "Advanced: paste an API key" — skips the username/password form entirely. */
    val apiKeyMode: Boolean = false,
    val apiKey: String = "",
    /** The landing (mode picker) or the active sub-flow step. */
    val step: LoginStep = LoginStep.MODE,
    /** Which card was chosen; drives mode-aware banner copy (brief 04 §B). */
    val mode: LoginMode = LoginMode.SERVER,
    val verifying: Boolean = false,
    /** The URL that actually passed verification (pinned; the chip shows this). */
    val verifiedUrl: String = "",
    // -- Device-only sub-flow (brief 04 §A) --
    val childName: String = "",
    val childBirthDate: String = "", // ISO yyyy-MM-dd; blank is valid (stored as today)
    // -- Cloud sub-flow (brief 04 §B) --
    val cloudTab: CloudTab = CloudTab.SIGN_IN,
    val cloudUsername: String = "",
    val cloudPassword: String = "",
    val cloudPassphrase: String = "",
    val cloudPassphraseConfirm: String = "",
    val cloudPassphraseVisible: Boolean = false,
    /** P0 (Jordan): create-account shows the data-safety gate BEFORE any network call. */
    val cloudCreateConfirmVisible: Boolean = false,
    /**
     * Dead-card guard (ux review): the cloud card renders — and CLOUD is selectable — only
     * when an auth backend exists (null in partially-shipped builds), so no tap is a no-op.
     */
    val cloudAvailable: Boolean = false,
    /**
     * P1 (QA 2026-10-31): a successful CLOUD auth landed directly on SignedIn, so a fresh
     * cloud account (no children server-side) reached a childless dashboard with inert quick
     * actions and no setup path — no event could ever be saved. True when the child-setup
     * step that just opened belongs to the cloud flow: startTracking then creates the LOCAL
     * Room row, pushes it to the cloud, and keeps the session backend CLOUD.
     */
    val pendingCloudChildSetup: Boolean = false,
) {
    val canContinue: Boolean
        get() = !verifying && serverUrl.isNotBlank()

    /** Live normalization hint — shown only while it differs from the typed text (UX spec §1). */
    val normalizedServer: NormalizedServerUrl?
        get() = if (serverUrl.isBlank()) null else normalizeServerUrl(serverUrl)

    val showNormalizationHint: Boolean
        get() = normalizedServer != null && normalizedServer?.url != serverUrl.trim()

    val isCleartext: Boolean get() = normalizedServer?.isCleartext == true

    /**
     * Device setup: name required; birth date OPTIONAL (blank = stored as today) but, when
     * typed, it must parse and never be in the future.
     */
    val childBirthDateValid: Boolean
        get() = if (childBirthDate.isBlank()) {
            true
        } else {
            runCatching {
                val parsed = java.time.LocalDate.parse(childBirthDate)
                !parsed.isAfter(java.time.LocalDate.now())
            }.getOrDefault(false)
        }

    val canStartTracking: Boolean
        get() = childName.isNotBlank() && childBirthDateValid

    /** Cloud field validation: mismatch is a live field error; the banner copy uses it too. */
    val passphraseMismatch: Boolean
        get() = cloudTab == CloudTab.CREATE_ACCOUNT &&
            cloudPassphrase.isNotEmpty() &&
            cloudPassphraseConfirm.isNotEmpty() &&
            cloudPassphrase != cloudPassphraseConfirm

    val canSubmitCloud: Boolean
        get() = when {
            submitting || step != LoginStep.CLOUD_ENTRY -> false
            passphraseMismatch -> false
            cloudTab == CloudTab.CREATE_ACCOUNT -> cloudUsername.isNotBlank() &&
                cloudPassword.isNotBlank() && cloudPassphrase.isNotBlank() &&
                cloudPassphraseConfirm.isNotBlank()
            else -> cloudUsername.isNotBlank() && cloudPassword.isNotBlank() && cloudPassphrase.isNotBlank()
        }

    val canSubmit: Boolean
        get() = when {
            submitting || step != LoginStep.CREDENTIALS -> false
            apiKeyMode -> serverUrl.isNotBlank() && apiKey.isNotBlank()
            else -> serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        }
}

sealed interface LoginEvent {
    data object SignedIn : LoginEvent
}

class LoginViewModel(
    private val loginFlow: LoginFlow,
    private val sessionStore: SessionStore,
    /**
     * Creates the local Room child (brief 05 §4/§5) and returns it: the DEVICE flow stops
     * there; the CLOUD flow hands it to [onCloudChildCreated] (the cloud record id IS the
     * local id — the pull path decrypts back into the same identity).
     */
    private val onCreateLocalChild: suspend (name: String, birthDate: String) -> app.dandle.domain.model.Child =
        { _, _ -> error("onCreateLocalChild not wired") },
    /** Dandle Cloud auth (brief 05 §5); null only in partially-shipped builds. */
    private val cloudAuth: CloudAuth? = null,
    /** Wraps the DEK into the device vault after a successful cloud auth (Keystore edge). */
    private val vaultCloudDek: suspend (dek: ByteArray) -> String = { "" },
    /**
     * P1 (QA 2026-10-31): asked AFTER saveCloudLogin persisted the fresh token, so the wiring
     * must read the session store (not stale mirrors). False routes a fresh account to child
     * setup; a throw must be caught by the wiring — the ViewModel fails open into the
     * dashboard when the backend is unreachable (never trap the user in setup).
     */
    private val cloudHasChildren: suspend () -> Boolean = { false },
    /** Cloud flow only: pushes the just-created local child as an encrypted cloud record. */
    private val onCloudChildCreated: suspend (child: app.dandle.domain.model.Child) -> Unit = { _ -> },
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState(cloudAvailable = cloudAuth != null))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<LoginEvent>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<LoginEvent> = _events.asSharedFlow()

    /** Set once the user confirms the cleartext dialog; reset on cancel. */
    private var cleartextAcknowledged = false

    init {
        // Pre-fill stored server URL + username (always; mandatory for the
        // session-expired path per UX spec §1, matches the original app otherwise).
        // An expired SERVER session lands DIRECTLY on the URL step — a re-auth user must
        // not re-pick a mode; the picker is only for first setup (brief 04 §B).
        viewModelScope.launch {
            val session = sessionStore.sessionFlow.firstOrNull()
            if (session != null) {
                _uiState.update {
                    it.copy(
                        serverUrl = it.serverUrl.ifBlank { session.serverUrl.orEmpty() },
                        username = it.username.ifBlank { session.username.orEmpty() },
                        cloudUsername = it.cloudUsername.ifBlank { session.cloudUsername.orEmpty() },
                        banner = if (session.sessionExpired) LoginBanner.SESSION_EXPIRED else it.banner,
                        sessionPrefill = session.sessionExpired,
                        mode = if (session.sessionExpired && session.backend == Backend.BABYBUDDY) LoginMode.SERVER else it.mode,
                        step = if (session.sessionExpired && session.backend == Backend.BABYBUDDY) LoginStep.URL else it.step,
                    )
                }
            }
        }
        // The ViewModel is store-backed and outlives single login attempts (see AppNavHost), so
        // the session-expired notice must be reactive: a 401 pause during sync (spec §6.3.6)
        // can arrive long after this instance was created, while the user is elsewhere.
        viewModelScope.launch {
            // P2 (QA 2026-10-31): sign-out must reset the picker. The Activity-scoped ViewModel
            // survives DashboardViewModel.signOut() and used to keep the dead sub-flow + typed
            // drafts (a device-mode sign-out landed back on child setup, pre-typed name intact).
            // This collector fires on EVERY session emission (clock-offset writes included), so
            // the reset keys on the logged-in → logged-out EDGE only — never on repeated
            // logged-out emissions, which would wipe typing during a fresh login. The
            // session-expired path below keeps its own pre-filled URL-step behavior.
            var wasLoggedIn: Boolean? = null // null until the first emission (no reset before that)
            sessionStore.sessionFlow.collect { session ->
                val previous = wasLoggedIn
                wasLoggedIn = session.isLoggedIn
                if (previous == true && !session.isLoggedIn && !session.sessionExpired) {
                    _uiState.update {
                        it.copy(
                            step = LoginStep.MODE,
                            mode = LoginMode.SERVER,
                            banner = null,
                            httpStatus = 0,
                            sessionPrefill = false,
                            submitting = false,
                            verifying = false,
                            verifiedUrl = "",
                            cleartextDialogVisible = false,
                            password = "",
                            passwordVisible = false,
                            apiKeyMode = false,
                            apiKey = "",
                            childName = "",
                            childBirthDate = "",
                            cloudTab = CloudTab.SIGN_IN,
                            cloudUsername = session.cloudUsername.orEmpty(),
                            cloudPassword = "",
                            cloudPassphrase = "",
                            cloudPassphraseConfirm = "",
                            cloudPassphraseVisible = false,
                            cloudCreateConfirmVisible = false,
                            pendingCloudChildSetup = false,
                            // serverUrl survives sign-out IN THE STORE on purpose (login
                            // pre-fill, original-app behavior): clear the draft, then
                            // re-hydrate from the fresh emission exactly like init does.
                            serverUrl = session.serverUrl.orEmpty(),
                            username = session.username.orEmpty(),
                        )
                    }
                }
                if (session.sessionExpired && _uiState.value.banner == null) {
                    _uiState.update {
                        it.copy(
                            banner = LoginBanner.SESSION_EXPIRED,
                            sessionPrefill = true,
                            mode = if (session.backend == Backend.BABYBUDDY) LoginMode.SERVER else it.mode,
                            step = when (session.backend) {
                                Backend.BABYBUDDY -> LoginStep.URL
                                else -> it.step
                            },
                        )
                    }
                }
            }
        }
    }

    // -- Mode picker -------------------------------------------------------------

    /**
     * Card tap = select and advance (brief 04 §B); drafts survive the detour. CLOUD without
     * an auth backend is a dead card: the picker never renders it, and a stray tap is ignored.
     */
    fun onModeSelected(mode: LoginMode) {
        if (mode == LoginMode.CLOUD && !_uiState.value.cloudAvailable) return
        val step = when (mode) {
            LoginMode.DEVICE -> LoginStep.DEVICE_CHILD
            LoginMode.SERVER -> LoginStep.URL
            LoginMode.CLOUD -> LoginStep.CLOUD_ENTRY
        }
        _uiState.update { it.copy(mode = mode, step = step, banner = null, sessionPrefill = false) }
    }

    /** Back from any sub-flow returns to MODE (never a sibling); drafts are kept. */
    fun onBackToMode() {
        if (_uiState.value.step == LoginStep.URL || _uiState.value.step == LoginStep.CREDENTIALS) {
            // SERVER keeps its own internal back (URL ↔ CREDENTIALS) at the top bar level:
            // from URL the only way out is the picker.
        }
        // Cloud child setup backs out like any sub-flow (state machine unchanged); the flag
        // must go with it — a later DEVICE-mode setup must keep its normal LOCAL semantics.
        _uiState.update { it.copy(step = LoginStep.MODE, banner = null, pendingCloudChildSetup = false) }
    }

    /** SERVER's internal back (verified-chip "Change" → URL step). */
    fun onBackToUrl() = _uiState.update {
        it.copy(step = LoginStep.URL, banner = null)
    }

    // -- Device-only sub-flow ------------------------------------------------------

    fun onChildNameChange(value: String) = _uiState.update { it.copy(childName = value, banner = null) }

    fun onChildBirthDateChange(isoDate: String) = _uiState.update { it.copy(childBirthDate = isoDate, banner = null) }

    fun startTracking() {
        val state = _uiState.value
        if (!state.canStartTracking || state.submitting) return
        _uiState.update { it.copy(submitting = true, banner = null) }
        viewModelScope.launch {
            // Birth date is optional (P0): a left-blank date is stored as TODAY so the age
            // display always has a real date and "not sure yet" never blocks setup.
            val birthDate = state.childBirthDate.ifBlank { java.time.LocalDate.now().toString() }
            val child = onCreateLocalChild(state.childName, birthDate)
            if (state.pendingCloudChildSetup) {
                // CLOUD flow (P1 fix): the local Room row is created above; push it as the
                // account's first cloud record. The session backend is ALREADY CLOUD
                // (saveCloudLogin ran at auth) — saveDeviceSession must NOT run, or it would
                // flip the install to LOCAL. A failed push (offline edge) never blocks
                // sign-in: the child stays local-only instead of dead-ending the user.
                try {
                    onCloudChildCreated(child)
                } catch (e: Exception) {
                    app.dandle.log.BbLog.d(
                        app.dandle.log.BbLog.TAG_LOGIN,
                        "cloud child push failed (child stays local-only): ${e.javaClass.simpleName}",
                    )
                }
            } else {
                sessionStore.saveDeviceSession()
            }
            _uiState.update { it.copy(submitting = false, pendingCloudChildSetup = false) }
            _events.tryEmit(LoginEvent.SignedIn)
        }
    }

    // -- Server sub-flow (pinned behavior, brief 04 §B) ---------------------------

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

    /** T8: toggle the "Advanced: paste an API key" mode (fields swap, banner clears). */
    fun onApiKeyModeToggle() = _uiState.update {
        it.copy(apiKeyMode = !it.apiKeyMode, banner = null, sessionPrefill = false, apiKey = "")
    }

    fun onApiKeyChange(value: String) = _uiState.update {
        it.copy(apiKey = value, banner = null, sessionPrefill = false)
    }

    /** Progressive step 1 → 2: probe the URL; any HTTP answer (even 401) passes. */
    fun onContinueFromUrl() {
        val state = _uiState.value
        if (state.step != LoginStep.URL || state.verifying || state.serverUrl.isBlank()) return
        if (state.isCleartext && !cleartextAcknowledged) {
            _uiState.update { it.copy(cleartextDialogVisible = true) }
            return
        }
        _uiState.update { it.copy(verifying = true, banner = null) }
        viewModelScope.launch {
            when (val result = loginFlow.verifyUrl(state.serverUrl)) {
                is UrlVerifyResult.Reached ->
                    _uiState.update {
                        it.copy(
                            step = LoginStep.CREDENTIALS,
                            verifying = false,
                            verifiedUrl = state.normalizedServer?.url ?: state.serverUrl,
                        )
                    }
                is UrlVerifyResult.HttpError ->
                    // The host answered — odd (no Baby Buddy API at that path), but reachable:
                    // let the user proceed; the full login will surface any real error.
                    _uiState.update {
                        it.copy(
                            step = LoginStep.CREDENTIALS,
                            verifying = false,
                            httpStatus = result.status,
                            verifiedUrl = state.normalizedServer?.url ?: state.serverUrl,
                        )
                    }
                UrlVerifyResult.Unreachable -> {
                    _uiState.update { it.copy(verifying = false, banner = LoginBanner.UNREACHABLE) }
                }
            }
        }
    }

    fun submit() {
        val state = _uiState.value
        when (state.step) {
            LoginStep.CREDENTIALS -> {
                if (!state.canSubmit) return
                if (state.isCleartext && !cleartextAcknowledged) {
                    _uiState.update { it.copy(cleartextDialogVisible = true) }
                    return
                }
                if (state.apiKeyMode) doTokenLogin() else doLogin()
            }
            LoginStep.CLOUD_ENTRY -> submitCloud()
            else -> Unit
        }
    }

    fun onCleartextContinue() {
        cleartextAcknowledged = true
        _uiState.update { it.copy(cleartextDialogVisible = false) }
        when (_uiState.value.step) {
            LoginStep.URL -> onContinueFromUrl()
            LoginStep.CREDENTIALS -> if (_uiState.value.apiKeyMode) doTokenLogin() else doLogin()
            else -> Unit
        }
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
            handleLoginResult(result)
        }
    }

    private fun doTokenLogin() {
        val state = _uiState.value
        _uiState.update { it.copy(submitting = true, banner = null) }
        viewModelScope.launch {
            handleLoginResult(loginFlow.loginWithToken(rawUrl = state.serverUrl, apiKey = state.apiKey))
        }
    }

    private fun handleLoginResult(result: LoginResult) {
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

    // -- Cloud sub-flow (brief 04 §B, brief 05 §5) ---------------------------------

    fun onCloudTabChange(tab: CloudTab) = _uiState.update {
        it.copy(cloudTab = tab, banner = null)
    }

    fun onCloudUsernameChange(value: String) = _uiState.update {
        it.copy(cloudUsername = value, banner = null)
    }

    fun onCloudPasswordChange(value: String) = _uiState.update {
        it.copy(cloudPassword = value, banner = null)
    }

    fun onCloudPassphraseChange(value: String) = _uiState.update {
        it.copy(cloudPassphrase = value, banner = null)
    }

    fun onCloudPassphraseConfirmChange(value: String) = _uiState.update {
        it.copy(cloudPassphraseConfirm = value, banner = null)
    }

    fun onCloudPassphraseVisibilityToggle() = _uiState.update {
        it.copy(cloudPassphraseVisible = !it.cloudPassphraseVisible)
    }

    private fun submitCloud() {
        val state = _uiState.value
        if (!state.canSubmitCloud) return
        if (state.cloudTab == CloudTab.CREATE_ACCOUNT) {
            // P0 (Jordan): creating an account is irreversible — the passphrase is the only
            // key and there is no reset. The data-safety gate must be confirmed BEFORE any
            // network call. Sign-in is NOT gated.
            _uiState.update { it.copy(cloudCreateConfirmVisible = true, banner = null) }
            return
        }
        doCloudAuth()
    }

    /** The gate's confirm: hide the dialog, then — and only then — hit the network. */
    fun onCloudCreateConfirmed() {
        if (!_uiState.value.cloudCreateConfirmVisible) return
        _uiState.update { it.copy(cloudCreateConfirmVisible = false) }
        doCloudAuth()
    }

    /** The gate's "Go back": typed values are kept — cancelling must not wipe the draft. */
    fun onCloudCreateCancelled() {
        _uiState.update { it.copy(cloudCreateConfirmVisible = false) }
    }

    private fun doCloudAuth() {
        val auth = cloudAuth ?: return
        val state = _uiState.value
        if (!state.canSubmitCloud) return
        _uiState.update { it.copy(submitting = true, banner = null) }
        viewModelScope.launch {
            val result = if (state.cloudTab == CloudTab.CREATE_ACCOUNT) {
                auth.signUp(state.cloudUsername.trim(), state.cloudPassword, state.cloudPassphrase)
            } else {
                auth.signIn(state.cloudUsername.trim(), state.cloudPassword, state.cloudPassphrase)
            }
            when (result) {
                is CloudLoginResult.Success -> {
                    val vault = vaultCloudDek(result.dek)
                    sessionStore.saveCloudLogin(result.token, result.envelope, result.username, result.userId, vault)
                    // P1 (QA 2026-10-31) fresh-account routing: create-account is ALWAYS a
                    // brand-new (childless) account; a sign-in asks the backend. The wiring
                    // reads the just-saved session (mirrors lag the store); a throw fails
                    // OPEN into the dashboard — a setup trap is worse than an empty dashboard.
                    val hasChildren = if (state.cloudTab == CloudTab.CREATE_ACCOUNT) {
                        false
                    } else {
                        try {
                            cloudHasChildren()
                        } catch (e: Exception) {
                            app.dandle.log.BbLog.d(
                                app.dandle.log.BbLog.TAG_LOGIN,
                                "cloud hasChildren check failed (fail open): ${e.javaClass.simpleName}",
                            )
                            true
                        }
                    }
                    if (hasChildren) {
                        _uiState.update { it.copy(submitting = false, banner = null) }
                        _events.tryEmit(LoginEvent.SignedIn)
                    } else {
                        _uiState.update {
                            it.copy(
                                submitting = false,
                                banner = null,
                                pendingCloudChildSetup = true,
                                step = LoginStep.DEVICE_CHILD,
                            )
                        }
                    }
                }
                is CloudLoginResult.Failure -> {
                    val banner = when (result.error) {
                        CloudError.UNREACHABLE -> LoginBanner.UNREACHABLE
                        CloudError.BAD_CREDENTIALS -> LoginBanner.BAD_CREDENTIALS
                        CloudError.USERNAME_TAKEN -> LoginBanner.USERNAME_TAKEN
                        CloudError.WRONG_PASSPHRASE -> LoginBanner.WRONG_PASSPHRASE
                        is CloudError.HTTP -> LoginBanner.HTTP_ERROR
                    }
                    val status = (result.error as? CloudError.HTTP)?.status ?: 0
                    _uiState.update { it.copy(submitting = false, banner = banner, httpStatus = status) }
                }
            }
        }
    }
}
