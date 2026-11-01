package app.dandle.ui.login

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import app.dandle.data.auth.LoginFlow
import app.dandle.data.backend.Backend
import app.dandle.data.cloud.CloudAuth
import app.dandle.data.cloud.CloudError
import app.dandle.data.cloud.CloudLoginResult
import app.dandle.data.prefs.SessionStore
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Mode picker + sub-flow state machine (brief 04 §B, brief 06): card tap advances, back
 * returns to the picker with drafts kept, device setup persists the LOCAL session through
 * the injected child hook, and the cloud create flow runs GENERATE → VERIFY (generated
 * passphrase, paste-to-confirm proof-of-backup, optional contact email) while the sign-in
 * tab stays one step.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LoginModePickerTest {

    /** One recorded auth call: which entry point ran, with exactly which arguments. */
    private data class AuthCall(
        val kind: String,
        val username: String,
        val password: String,
        val passphrase: String,
        val contactEmail: String,
    )

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionFile: okio.Path
    private lateinit var sessionStore: SessionStore
    private lateinit var viewModel: LoginViewModel

    private var createdChildName: String? = null
    private var createdBirthDate: String? = null

    /** The child returned by the onCreateLocalChild fake (the cloud hook receives it). */
    private var localChild: app.dandle.domain.model.Child? = null

    /** Children the cloud child hook received (startTracking in the cloud child-setup flow). */
    private val cloudCreatedChildren = mutableListOf<app.dandle.domain.model.Child>()

    /** Records every auth call with its arguments — gating tests assert order AND args. */
    private val authCalls = mutableListOf<AuthCall>()

    /** Configurable fake results; default failure (gating tests need no happy path). */
    private var signUpResult: CloudLoginResult = CloudLoginResult.Failure(CloudError.UNREACHABLE)
    private var signInResult: CloudLoginResult = CloudLoginResult.Failure(CloudError.UNREACHABLE)

    /** Records the auth call; returns the configured result. */
    private val fakeCloudAuth = object : CloudAuth {
        override suspend fun signUp(
            username: String,
            password: String,
            passphrase: String,
            contactEmail: String,
        ): CloudLoginResult {
            authCalls += AuthCall("signUp", username, password, passphrase, contactEmail)
            return signUpResult
        }

        override suspend fun signIn(username: String, password: String, passphrase: String): CloudLoginResult {
            authCalls += AuthCall("signIn", username, password, passphrase, contactEmail = "")
            return signInResult
        }
    }

    private fun cloudSuccess() =
        CloudLoginResult.Success(
            token = "pb-token",
            envelope = """{"salt":"x"}""",
            username = "nick",
            userId = "user-1",
            dek = ByteArray(0),
        )

    /**
     * Builds a ViewModel wired for the cloud child-routing tests: the local child hook records
     * and returns the created child (real Navigation shape), the cloud hook records, and
     * [hasChildren] fakes the backend probe.
     */
    private fun buildCloudRoutingVm(hasChildren: suspend () -> Boolean): LoginViewModel =
        LoginViewModel(
            loginFlow = LoginFlow(
                client = OkHttpClient.Builder().followRedirects(false).build(),
                json = Json { ignoreUnknownKeys = true; explicitNulls = false },
                sessionStore = sessionStore,
            ),
            sessionStore = sessionStore,
            onCreateLocalChild = { name, birthDate ->
                createdChildName = name
                createdBirthDate = birthDate
                app.dandle.domain.model.Child(
                    id = "local-child-1",
                    firstName = name,
                    lastName = null,
                    slug = "local-child-1",
                    birthDate = birthDate,
                    birthTime = null,
                ).also { localChild = it }
            },
            cloudAuth = fakeCloudAuth,
            cloudHasChildren = hasChildren,
            onCloudChildCreated = { child -> cloudCreatedChildren += child },
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionFile = java.nio.file.Files.createTempDirectory("picker-test")
            .resolve("session.preferences_pb")
            .toString()
            .toPath()
        sessionStore = SessionStore(sessionFile)
        viewModel = LoginViewModel(
            loginFlow = LoginFlow(
                client = OkHttpClient.Builder().followRedirects(false).build(),
                json = Json { ignoreUnknownKeys = true; explicitNulls = false },
                sessionStore = sessionStore,
            ),
            sessionStore = sessionStore,
            onCreateLocalChild = { name, birthDate ->
                createdChildName = name
                createdBirthDate = birthDate
                app.dandle.domain.model.Child(
                    id = "local-child-1",
                    firstName = name,
                    lastName = null,
                    slug = "local-child-1",
                    birthDate = birthDate,
                    birthTime = null,
                ).also { localChild = it }
            },
            cloudAuth = fakeCloudAuth,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun advance() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            org.robolectric.Robolectric.flushForegroundThreadScheduler()
            Thread.sleep(20)
        }
    }

    @Test
    fun `the picker is the landing and each card advances to its own sub-flow`() {
        assertEquals(LoginStep.MODE, viewModel.uiState.value.step)

        viewModel.onModeSelected(LoginMode.DEVICE)
        assertEquals(LoginStep.DEVICE_CHILD, viewModel.uiState.value.step)
        viewModel.onBackToMode()

        viewModel.onModeSelected(LoginMode.CLOUD)
        assertEquals(LoginStep.CLOUD_ENTRY, viewModel.uiState.value.step)
        viewModel.onBackToMode()

        viewModel.onModeSelected(LoginMode.SERVER)
        assertEquals(LoginStep.URL, viewModel.uiState.value.step)
    }

    @Test
    fun `back to the mode picker keeps typed drafts`() {
        viewModel.onModeSelected(LoginMode.SERVER)
        viewModel.onServerUrlChange("http://example.com")
        viewModel.onModeSelected(LoginMode.DEVICE)
        viewModel.onChildNameChange("Milo")

        viewModel.onBackToMode()

        assertEquals(LoginStep.MODE, viewModel.uiState.value.step)
        viewModel.onModeSelected(LoginMode.SERVER)
        assertEquals("server URL draft survives the detour", "http://example.com", viewModel.uiState.value.serverUrl)
        viewModel.onModeSelected(LoginMode.DEVICE)
        assertEquals("child name draft survives the detour", "Milo", viewModel.uiState.value.childName)
    }

    @Test
    fun `device setup validates and persists the LOCAL session`() = runBlocking {
        viewModel.onModeSelected(LoginMode.DEVICE)
        assertTrue("an auth backend makes the cloud card real", viewModel.uiState.value.cloudAvailable)

        // Gating: the name is required. The birth date is OPTIONAL, but a typed date may
        // never be in the future.
        assertFalse(viewModel.uiState.value.canStartTracking)
        viewModel.onChildNameChange("Milo")
        assertTrue("a name alone enables Start tracking", viewModel.uiState.value.canStartTracking)
        viewModel.onChildBirthDateChange("2099-01-01")
        assertFalse("a future birth date is rejected", viewModel.uiState.value.canStartTracking)

        viewModel.onChildBirthDateChange("2026-01-14")
        assertTrue(viewModel.uiState.value.canStartTracking)

        var signedIn = false
        val collector = launch(Dispatchers.Unconfined) {
            viewModel.events.collect { if (it is LoginEvent.SignedIn) signedIn = true }
        }
        viewModel.startTracking()
        advance()
        collector.cancel()

        assertTrue(signedIn)
        assertEquals("Milo", createdChildName)
        assertEquals("2026-01-14", createdBirthDate)
        advance()
        val session = sessionStore.sessionFlow.firstOrNull()!!
        assertEquals(Backend.LOCAL, session.backend)
        assertTrue("LOCAL session counts as logged in once initialized", session.isLoggedIn)
    }

    @Test
    fun `a blank birth date passes Start tracking and is stored as today`() = runBlocking {
        viewModel.onModeSelected(LoginMode.DEVICE)
        viewModel.onChildNameChange("Milo")
        assertTrue("name alone is enough — the date is optional", viewModel.uiState.value.canStartTracking)

        var signedIn = false
        val collector = launch(Dispatchers.Unconfined) {
            viewModel.events.collect { if (it is LoginEvent.SignedIn) signedIn = true }
        }
        viewModel.startTracking()
        advance()
        collector.cancel()

        assertTrue(signedIn)
        assertEquals("Milo", createdChildName)
        assertEquals(
            "a blank birth date reaches the create hook as today",
            java.time.LocalDate.now().toString(),
            createdBirthDate,
        )
    }

    // -- Cloud create flow: GENERATE → VERIFY (brief 06 §4) ----------------------------

    /**
     * Drives the create tab from tab switch to the VERIFY step: asserts the pre-generated
     * passphrase, fills the account section, presses Continue, and returns the passphrase.
     */
    private fun driveCreateToVerify(vm: LoginViewModel = viewModel, email: String = ""): String {
        vm.onModeSelected(LoginMode.CLOUD)
        vm.onCloudTabChange(CloudTab.CREATE_ACCOUNT)
        val generated = vm.uiState.value.cloudCreatePassphrase
        assertTrue("tab switch pre-fills a generated passphrase", generated.isNotBlank())
        assertEquals("the flow starts on the generate step", PassphraseStep.GENERATE, vm.uiState.value.cloudPassphraseStep)
        assertTrue("a fresh generated passphrase passes the Continue gate", vm.uiState.value.canContinuePassphrase)
        vm.onCloudUsernameChange("nick")
        vm.onCloudPasswordChange("pw")
        vm.onCloudPasswordConfirmChange("pw")
        if (email.isNotEmpty()) vm.onContactEmailChange(email)
        vm.onCloudPassphraseContinue()
        assertEquals(PassphraseStep.VERIFY, vm.uiState.value.cloudPassphraseStep)
        return generated
    }

    @Test
    fun `continue on the generate step lands on verify and the paste gates submission`() {
        val generated = driveCreateToVerify()

        assertFalse("a blank paste cannot submit", viewModel.uiState.value.canSubmitCloud)
        viewModel.onCloudPassphraseVerifyChange("not-the-passphrase")
        assertTrue("a wrong paste is a live field error", viewModel.uiState.value.cloudVerifyMismatch)
        assertFalse("a wrong paste gates the account creation", viewModel.uiState.value.canSubmitCloud)

        viewModel.onCloudPassphraseVerifyChange(generated)
        assertFalse(viewModel.uiState.value.cloudVerifyMismatch)
        assertTrue("the matching paste unlocks Create account", viewModel.uiState.value.canSubmitCloud)
    }

    @Test
    fun `submit on the generate step is a no op`() {
        driveCreateToVerify()
        viewModel.onCloudPassphraseBack() // even with every field filled, GENERATE cannot submit
        viewModel.onCloudUsernameChange("nick")
        viewModel.onCloudPasswordChange("pw")
        viewModel.onCloudPasswordConfirmChange("pw")
        assertEquals(PassphraseStep.GENERATE, viewModel.uiState.value.cloudPassphraseStep)
        assertFalse("the two-step flow must gate the network behind VERIFY", viewModel.uiState.value.canSubmitCloud)

        viewModel.submit()
        advance()

        assertTrue("no auth call may run from the generate step", authCalls.isEmpty())
        assertEquals("a no-op submit never moves the flow", PassphraseStep.GENERATE, viewModel.uiState.value.cloudPassphraseStep)
    }

    @Test
    fun `the correct paste calls signUp with the passphrase and contact email`() = runBlocking {
        val generated = driveCreateToVerify(email = "jordan@example.com")
        viewModel.onCloudPassphraseVerifyChange(generated)
        viewModel.submit()
        advance()

        val call = authCalls.single()
        assertEquals("signUp", call.kind)
        assertEquals("nick", call.username)
        assertEquals("pw", call.password)
        assertEquals("the GENERATED passphrase is the account key, not the paste alone", generated, call.passphrase)
        assertEquals("jordan@example.com", call.contactEmail)
    }

    @Test
    fun `a blank contact email is valid and omits nothing on submit`() = runBlocking {
        val generated = driveCreateToVerify() // email left blank
        viewModel.onCloudPassphraseVerifyChange(generated)
        assertTrue(viewModel.uiState.value.canSubmitCloud)
        viewModel.submit()
        advance()

        val call = authCalls.single()
        assertEquals("signUp", call.kind)
        assertEquals("optional email stays blank when not typed", "", call.contactEmail)
    }

    @Test
    fun `an invalid contact email blocks submit until fixed`() {
        driveCreateToVerify(email = "not-an-email")
        viewModel.onCloudPassphraseVerifyChange(viewModel.uiState.value.cloudCreatePassphrase)

        assertFalse("an email without @ fails live validation", viewModel.uiState.value.contactEmailValid)
        assertFalse("invalid email gates the account creation", viewModel.uiState.value.canSubmitCloud)

        viewModel.onContactEmailChange("jordan@example.com")
        assertTrue(viewModel.uiState.value.contactEmailValid)
        assertTrue(viewModel.uiState.value.canSubmitCloud)
    }

    @Test
    fun `create my own switches to an editable passphrase and enforces the minimum length`() {
        viewModel.onModeSelected(LoginMode.CLOUD)
        viewModel.onCloudTabChange(CloudTab.CREATE_ACCOUNT)
        val generated = viewModel.uiState.value.cloudCreatePassphrase

        viewModel.onCloudPassphraseCreateOwn()

        assertTrue("custom mode is one-way for the session", viewModel.uiState.value.cloudCreatePassphraseCustom)
        assertEquals("the generated value is cleared for typing", "", viewModel.uiState.value.cloudCreatePassphrase)
        assertFalse("blank custom value cannot continue", viewModel.uiState.value.canContinuePassphrase)
        assertFalse("regenerate is inert in custom mode", run {
            viewModel.onCloudPassphraseRegenerate()
            viewModel.uiState.value.cloudCreatePassphrase.isNotBlank()
        })

        viewModel.onCloudCreatePassphraseChange("short")
        assertFalse("a short custom passphrase cannot continue", viewModel.uiState.value.canContinuePassphrase)
        viewModel.onCloudCreatePassphraseChange("a-longer-custom-passphrase")
        assertTrue("a long-enough custom passphrase continues", viewModel.uiState.value.canContinuePassphrase)
        assertNotEquals("custom value is the user's own", generated, viewModel.uiState.value.cloudCreatePassphrase)
    }

    @Test
    fun `regenerate replaces the generated passphrase in place`() {
        viewModel.onModeSelected(LoginMode.CLOUD)
        viewModel.onCloudTabChange(CloudTab.CREATE_ACCOUNT)
        val first = viewModel.uiState.value.cloudCreatePassphrase

        viewModel.onCloudPassphraseRegenerate()

        assertFalse("still generated mode", viewModel.uiState.value.cloudCreatePassphraseCustom)
        assertTrue("still submittable (non-blank)", viewModel.uiState.value.canContinuePassphrase)
        assertNotEquals("a fresh passphrase is generated", first, viewModel.uiState.value.cloudCreatePassphrase)
    }

    @Test
    fun `back from verify keeps the passphrase and returns to generate`() {
        val generated = driveCreateToVerify()
        viewModel.onCloudPassphraseVerifyChange("oops-typo")

        viewModel.onCloudPassphraseBack()

        assertEquals(PassphraseStep.GENERATE, viewModel.uiState.value.cloudPassphraseStep)
        assertEquals("the passphrase value survives the rewind", generated, viewModel.uiState.value.cloudCreatePassphrase)
        assertTrue("Continue is available again", viewModel.uiState.value.canContinuePassphrase)
        assertFalse("no auth call may have run", authCalls.isNotEmpty())
    }

    // -- Cloud sign-in tab: unchanged one-step flow -------------------------------------

    @Test
    fun `sign-in tab stays one step and calls signIn immediately`() = runBlocking {
        viewModel.onModeSelected(LoginMode.CLOUD) // default tab is SIGN_IN
        fillValidSignInForm()
        assertTrue(viewModel.uiState.value.canSubmitCloud)

        viewModel.submit()
        advance()

        val call = authCalls.single()
        assertEquals("signIn", call.kind)
        assertEquals("nick", call.username)
        assertEquals("secret", call.passphrase)
        assertEquals("sign-in never enters the passphrase state machine", PassphraseStep.GENERATE, viewModel.uiState.value.cloudPassphraseStep)
    }

    @Test
    fun `password confirmation mismatch gates the create flow`() {
        driveCreateToVerify()
        viewModel.onCloudPasswordConfirmChange("different")
        viewModel.onCloudPassphraseVerifyChange(viewModel.uiState.value.cloudCreatePassphrase)

        assertTrue("confirm-vs-password is a live field error", viewModel.uiState.value.cloudPasswordMismatch)
        assertFalse("mismatched passwords gate the account creation", viewModel.uiState.value.canSubmitCloud)

        viewModel.onCloudPasswordConfirmChange("pw")
        assertFalse(viewModel.uiState.value.cloudPasswordMismatch)
        assertTrue(viewModel.uiState.value.canSubmitCloud)
    }

    @Test
    fun `without a cloud auth backend the cloud mode is a dead card`() {
        val vm = LoginViewModel(
            loginFlow = LoginFlow(
                client = OkHttpClient.Builder().followRedirects(false).build(),
                json = Json { ignoreUnknownKeys = true; explicitNulls = false },
                sessionStore = sessionStore,
            ),
            sessionStore = sessionStore,
        )
        assertFalse("no CloudAuth -> cloud is not offered", vm.uiState.value.cloudAvailable)
        vm.onModeSelected(LoginMode.CLOUD)
        assertEquals("CLOUD without a backend must be ignored, never open the entry step", LoginStep.MODE, vm.uiState.value.step)
        vm.onModeSelected(LoginMode.DEVICE)
        assertEquals("device setup stays available", LoginStep.DEVICE_CHILD, vm.uiState.value.step)
    }

    @Test
    fun `signing out resets to the picker with drafts cleared`() = runBlocking {
        // A stored server login keeps server_url in the store — exactly what
        // SessionStore.clear() deliberately preserves for the next login's pre-fill.
        sessionStore.saveLogin("http://10.0.2.2:8000", apiKey = "key", username = "admin")
        advance()
        viewModel.onModeSelected(LoginMode.DEVICE)
        viewModel.onChildNameChange("Milo")
        viewModel.startTracking()
        advance()
        assertEquals("Milo", viewModel.uiState.value.childName)

        // The Activity-scoped ViewModel keeps holding drafts after sign-in — then the
        // dashboard's sign-out clears the session underneath it.
        viewModel.onChildNameChange("Milo Jr")
        sessionStore.clear()
        advance()

        assertEquals("sign-out lands on the mode picker, not the dead sub-flow", LoginStep.MODE, viewModel.uiState.value.step)
        assertEquals("the child-name draft is wiped", "", viewModel.uiState.value.childName)
        assertEquals(
            "serverUrl re-hydrates from the kept store value (login pre-fill)",
            "http://10.0.2.2:8000",
            viewModel.uiState.value.serverUrl,
        )
        assertEquals("username re-hydrates from the cleared session (empty)", "", viewModel.uiState.value.username)

        // The reset is a one-shot logged-in -> logged-out EDGE: repeated logged-out emissions
        // (clock-offset writes, a second clear) must never wipe typing during a fresh login.
        viewModel.onModeSelected(LoginMode.DEVICE)
        viewModel.onChildNameChange("Fresh")
        sessionStore.clear()
        advance()
        assertEquals("a repeated logged-out emission keeps typing", "Fresh", viewModel.uiState.value.childName)
        assertEquals(LoginStep.DEVICE_CHILD, viewModel.uiState.value.step)
    }

    private fun fillValidSignInForm(vm: LoginViewModel = viewModel) {
        vm.onCloudUsernameChange("nick")
        vm.onCloudPasswordChange("pw")
        vm.onCloudPassphraseChange("secret")
    }

    // -- Cloud child routing (P1 QA 2026-10-31) ---------------------------------------
    // A fresh cloud account used to land SignedIn on a childless dashboard with no setup
    // path — no child could ever exist, so no event could ever be saved on cloud.

    @Test
    fun `create-account success routes a fresh account to cloud child setup`() = runBlocking {
        signUpResult = cloudSuccess() // create-account: routing is unconditional, no probe
        var probeCalls = 0
        val vm = buildCloudRoutingVm(hasChildren = { probeCalls += 1; false })

        var signedIn = false
        val collector = launch(Dispatchers.Unconfined) {
            vm.events.collect { if (it is LoginEvent.SignedIn) signedIn = true }
        }
        driveCreateToVerify(vm)
        vm.onCloudPassphraseVerifyChange(vm.uiState.value.cloudCreatePassphrase)
        vm.submit()
        advance()
        collector.cancel()

        assertEquals("signUp ran exactly once, after the paste", listOf("signUp"), authCalls.map { it.kind })
        assertEquals("child setup is the next screen, not the dashboard", LoginStep.DEVICE_CHILD, vm.uiState.value.step)
        assertTrue("startTracking must know this setup belongs to the cloud flow", vm.uiState.value.pendingCloudChildSetup)
        assertFalse("no SignedIn while setup is pending", signedIn)
        assertEquals("create-account never consults the has-children probe", 0, probeCalls)
        // The session is ALREADY CLOUD (saveCloudLogin ran) — setup completes it, not re-auth.
        val session = sessionStore.sessionFlow.firstOrNull()!!
        assertEquals(Backend.CLOUD, session.backend)
        assertTrue("a saved cloud login counts as logged in during child setup", session.isLoggedIn)
    }

    @Test
    fun `cloud child setup startTracking pushes the child and keeps the backend CLOUD`() = runBlocking {
        signUpResult = cloudSuccess()
        val vm = buildCloudRoutingVm(hasChildren = { false })
        driveCreateToVerify(vm)
        vm.onCloudPassphraseVerifyChange(vm.uiState.value.cloudCreatePassphrase)
        vm.submit()
        advance()
        assertEquals(LoginStep.DEVICE_CHILD, vm.uiState.value.step)

        var signedIn = false
        val collector = launch(Dispatchers.Unconfined) {
            vm.events.collect { if (it is LoginEvent.SignedIn) signedIn = true }
        }
        vm.onChildNameChange("Milo")
        vm.startTracking()
        advance()
        collector.cancel()

        assertTrue(signedIn)
        assertEquals("the LOCAL Room child was created by the first hook", "Milo", createdChildName)
        assertEquals("the cloud hook received the SAME created child", listOf("local-child-1"), cloudCreatedChildren.map { it.id })
        val session = sessionStore.sessionFlow.firstOrNull()!!
        assertEquals(
            "saveDeviceSession must NOT run on the cloud path — the backend stays CLOUD",
            Backend.CLOUD,
            session.backend,
        )
        assertTrue(session.isLoggedIn)
    }

    @Test
    fun `sign-in to an account that has children goes straight to SignedIn`() = runBlocking {
        signInResult = cloudSuccess()
        val vm = buildCloudRoutingVm(hasChildren = { true })

        var signedIn = false
        val collector = launch(Dispatchers.Unconfined) {
            vm.events.collect { if (it is LoginEvent.SignedIn) signedIn = true }
        }
        vm.onModeSelected(LoginMode.CLOUD) // default tab is SIGN_IN
        fillValidSignInForm(vm)
        vm.submit()
        advance()
        collector.cancel()

        assertTrue(signedIn)
        assertEquals(
            "an account with children must not be detoured through child setup",
            LoginStep.CLOUD_ENTRY,
            vm.uiState.value.step,
        )
        assertFalse(vm.uiState.value.pendingCloudChildSetup)
    }

    @Test
    fun `a failing has-children probe fails OPEN into the dashboard`() = runBlocking {
        signInResult = cloudSuccess()
        val vm = buildCloudRoutingVm(hasChildren = { throw java.io.IOException("offline") })

        var signedIn = false
        val collector = launch(Dispatchers.Unconfined) {
            vm.events.collect { if (it is LoginEvent.SignedIn) signedIn = true }
        }
        vm.onModeSelected(LoginMode.CLOUD)
        fillValidSignInForm(vm)
        vm.submit()
        advance()
        collector.cancel()

        assertTrue("an unreachable probe must never trap the user in setup", signedIn)
        assertFalse(vm.uiState.value.pendingCloudChildSetup)
    }

    @Test
    fun `backing out of cloud child setup returns to MODE and clears the cloud flag`() = runBlocking {
        signUpResult = cloudSuccess()
        val vm = buildCloudRoutingVm(hasChildren = { false })
        driveCreateToVerify(vm)
        vm.onCloudPassphraseVerifyChange(vm.uiState.value.cloudCreatePassphrase)
        vm.submit()
        advance()
        assertEquals(LoginStep.DEVICE_CHILD, vm.uiState.value.step)

        vm.onBackToMode()
        advance()

        assertEquals("the state machine is unchanged: back returns to the picker", LoginStep.MODE, vm.uiState.value.step)
        assertFalse(
            "the flag must not leak into a later DEVICE setup (it would push to cloud and skip saveDeviceSession)",
            vm.uiState.value.pendingCloudChildSetup,
        )
    }

}
