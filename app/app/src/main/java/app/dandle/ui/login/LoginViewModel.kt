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
import app.dandle.domain.PassphraseGenerator
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

/** Cloud sub-flow tabs (brief 04 §B): each tab renders its own field set. */
enum class CloudTab { SIGN_IN, CREATE_ACCOUNT }

/**
 * Create-tab passphrase state machine (brief 06 §4): GENERATE shows the pre-filled generated
 * passphrase to save; VERIFY is the paste-to-confirm proof-of-backup. Both live inside
 * CLOUD_ENTRY; the sign-in tab never enters the machine.
 */
enum class PassphraseStep { GENERATE, VERIFY }

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
    // -- Cloud sub-flow (brief 04 §B, brief 05 §5, brief 06) --
    val cloudTab: CloudTab = CloudTab.SIGN_IN,
    val cloudUsername: String = "",
    val cloudPassword: String = "",
    val cloudPassphrase: String = "",
    val cloudPassphraseVisible: Boolean = false,
    // -- Cloud create-account (brief 06 §2-§4) --
    /** Optional contact address (brief 06 §2): service-change warnings only, never marketing. */
    val contactEmail: String = "",
    /** Account password confirmation (brief 06 §3): must equal [cloudPassword] to submit. */
    val cloudPasswordConfirm: String = "",
    /** The create tab's passphrase working value (generated or user-chosen). */
    val cloudCreatePassphrase: String = "",
    /** True after "Create my own": field starts empty + obfuscated, regenerator disabled. */
    val cloudCreatePassphraseCustom: Boolean = false,
    val cloudPassphraseStep: PassphraseStep = PassphraseStep.GENERATE,
    /** The VERIFY step's paste input (proof-of-backup; must equal [cloudCreatePassphrase]). */
    val cloudPassphraseVerify: String = "",
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

    /**
     * Optional email (brief 06 §2): blank is valid; a non-blank value must contain "@" —
     * a live field error that blocks submit (no regex pedantry, just an obvious-catch).
     */
    val contactEmailValid: Boolean
        get() = contactEmail.isBlank() || contactEmail.contains("@")

    /** Account-password confirmation (brief 06 §3): live field error once both are typed. */
    val cloudPasswordMismatch: Boolean
        get() = cloudTab == CloudTab.CREATE_ACCOUNT &&
            cloudPasswordConfirm.isNotEmpty() &&
            cloudPasswordConfirm != cloudPassword

    /** VERIFY step live error: non-empty paste that doesn't match the saved passphrase. */
    val cloudVerifyMismatch: Boolean
        get() = cloudPassphraseStep == PassphraseStep.VERIFY &&
            cloudPassphraseVerify.isNotEmpty() &&
            cloudPassphraseVerify != cloudCreatePassphrase

    /** GENERATE step's Continue gate: non-blank; a custom (human-chosen) value needs >= 8 chars. */
    val canContinuePassphrase: Boolean
        get() = cloudCreatePassphrase.isNotBlank() &&
            (!cloudCreatePassphraseCustom || cloudCreatePassphrase.length >= 8)

    /**
     * Cloud auth submit gate. Sign-in is one step (username + password + passphrase). The
     * create flow (brief 06 §4) only submits from VERIFY with the paste matching the saved
     * passphrase and the account fields valid — the old confirm-dialog gate is replaced by
     * the two-step proof-of-backup itself.
     */
    val canSubmitCloud: Boolean
        get() = when {
            submitting || step != LoginStep.CLOUD_ENTRY -> false
            cloudTab == CloudTab.SIGN_IN ->
                cloudUsername.isNotBlank() && cloudPassword.isNotBlank() && cloudPassphrase.isNotBlank()
            else ->
                cloudPassphraseStep == PassphraseStep.VERIFY &&
                    cloudUsername.isNotBlank() && cloudPassword.isNotBlank() &&
                    cloudPasswordConfirm == cloudPassword &&
                    contactEmailValid &&
                    cloudCreatePassphrase.isNotBlank() &&
                    cloudPassphraseVerify == cloudCreatePassphrase
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
                            cloudPassphraseVisible = false,
                            contactEmail = "",
                            cloudPasswordConfirm = "",
                            cloudCreatePassphrase = "",
                            cloudCreatePassphraseCustom = false,
                            cloudPassphraseStep = PassphraseStep.GENERATE,
                            cloudPassphraseVerify = "",
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

    // -- Cloud sub-flow (brief 04 §B, brief 05 §5, brief 06 §2-§4) -----------------

    /**
     * Tab switch keeps typed drafts. First entry to the create tab PRE-FILLS a generated
     * passphrase (brief 06 §4): the field arrives readable, ready to save, before any typing.
     */
    fun onCloudTabChange(tab: CloudTab) = _uiState.update {
        val preGenerated = if (tab == CloudTab.CREATE_ACCOUNT && it.cloudCreatePassphrase.isBlank()) {
            it.copy(cloudCreatePassphrase = PassphraseGenerator.generate())
        } else {
            it
        }
        preGenerated.copy(cloudTab = tab, banner = null)
    }

    fun onCloudUsernameChange(value: String) = _uiState.update {
        it.copy(cloudUsername = value, banner = null)
    }

    fun onCloudPasswordChange(value: String) = _uiState.update {
        it.copy(cloudPassword = value, banner = null)
    }

    fun onCloudPasswordConfirmChange(value: String) = _uiState.update {
        it.copy(cloudPasswordConfirm = value, banner = null)
    }

    fun onContactEmailChange(value: String) = _uiState.update {
        it.copy(contactEmail = value, banner = null)
    }

    fun onCloudPassphraseChange(value: String) = _uiState.update {
        it.copy(cloudPassphrase = value, banner = null)
    }

    fun onCloudPassphraseVisibilityToggle() = _uiState.update {
        it.copy(cloudPassphraseVisible = !it.cloudPassphraseVisible)
    }

    /** The create tab's passphrase field (generated mode shows it editable + readable). */
    fun onCloudCreatePassphraseChange(value: String) = _uiState.update {
        it.copy(cloudCreatePassphrase = value, banner = null)
    }

    /**
     * Regenerate (brief 06 §4): only meaningful while still in generated mode — a no-op once
     * the user chose their own passphrase (that value must never be silently overwritten).
     */
    fun onCloudPassphraseRegenerate() = _uiState.update {
        if (it.cloudTab != CloudTab.CREATE_ACCOUNT || it.cloudCreatePassphraseCustom) {
            it
        } else {
            it.copy(
                cloudCreatePassphrase = PassphraseGenerator.generate(),
                cloudPassphraseVerify = "",
                banner = null,
            )
        }
    }

    /**
     * "Create my own" (brief 06 §4): clears the field to editable-empty, obfuscated with the
     * standard visibility toggle. One-way for the session — regenerating stays off so a typed
     * passphrase is never clobbered.
     */
    fun onCloudPassphraseCreateOwn() = _uiState.update {
        if (it.cloudTab != CloudTab.CREATE_ACCOUNT) {
            it
        } else {
            it.copy(
                cloudCreatePassphraseCustom = true,
                cloudCreatePassphrase = "",
                cloudPassphraseVisible = false,
                cloudPassphraseVerify = "",
                banner = null,
            )
        }
    }

    /** GENERATE → VERIFY (step 2 of 2): the paste field starts empty for a fresh paste. */
    fun onCloudPassphraseContinue() {
        val state = _uiState.value
        if (state.cloudTab != CloudTab.CREATE_ACCOUNT || !state.canContinuePassphrase) return
        _uiState.update { it.copy(cloudPassphraseStep = PassphraseStep.VERIFY, cloudPassphraseVerify = "", banner = null) }
    }

    /** VERIFY step paste input; mismatch surfaces live while typing. */
    fun onCloudPassphraseVerifyChange(value: String) = _uiState.update {
        it.copy(cloudPassphraseVerify = value, banner = null)
    }

    /** Back from VERIFY to GENERATE: the passphrase value is kept, only the step rewinds. */
    fun onCloudPassphraseBack() {
        if (_uiState.value.cloudPassphraseStep != PassphraseStep.VERIFY) return
        _uiState.update { it.copy(cloudPassphraseStep = PassphraseStep.GENERATE, banner = null) }
    }

    private fun submitCloud() {
        val state = _uiState.value
        if (!state.canSubmitCloud) return
        // Brief 06 §4: the confirm dialog is gone — canSubmitCloud already guarantees the
        // create flow is on VERIFY with the paste-to-confirm proof passed. Sign-in submits
        // directly, exactly as before.
        doCloudAuth()
    }

    private fun doCloudAuth() {
        val auth = cloudAuth ?: return
        val state = _uiState.value
        if (!state.canSubmitCloud) return
        _uiState.update { it.copy(submitting = true, banner = null) }
        viewModelScope.launch {
            val result = if (state.cloudTab == CloudTab.CREATE_ACCOUNT) {
                auth.signUp(
                    username = state.cloudUsername.trim(),
                    password = state.cloudPassword,
                    passphrase = state.cloudCreatePassphrase,
                    contactEmail = state.contactEmail.trim(),
                )
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
