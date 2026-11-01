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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Mode picker + sub-flow state machine (brief 04 §B): card tap advances, back returns to the
 * picker with drafts kept, device setup persists the LOCAL session through the injected child
 * hook, and the cloud form's validation gates submit (mismatch, missing fields).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LoginModePickerTest {

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

    /** Records which auth entry points ran ("signUp"/"signIn") — the gate must order them. */
    private val authCalls = mutableListOf<String>()

    /** Configurable fake results; default failure (gating tests need no happy path). */
    private var signUpResult: CloudLoginResult = CloudLoginResult.Failure(CloudError.UNREACHABLE)
    private var signInResult: CloudLoginResult = CloudLoginResult.Failure(CloudError.UNREACHABLE)

    /** Records the auth call; returns the configured result. */
    private val fakeCloudAuth = object : CloudAuth {
        override suspend fun signUp(username: String, password: String, passphrase: String): CloudLoginResult {
            authCalls += "signUp"
            return signUpResult
        }

        override suspend fun signIn(username: String, password: String, passphrase: String): CloudLoginResult {
            authCalls += "signIn"
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

    @Test
    fun `cloud form gates submit on mismatch and missing fields`() {
        viewModel.onModeSelected(LoginMode.CLOUD)
        viewModel.onCloudTabChange(CloudTab.CREATE_ACCOUNT)
        viewModel.onCloudUsernameChange("nick")
        viewModel.onCloudPasswordChange("pw")
        viewModel.onCloudPassphraseChange("secret")

        assertFalse("confirm missing", viewModel.uiState.value.canSubmitCloud)
        viewModel.onCloudPassphraseConfirmChange("different")
        assertTrue("mismatch is a live field error", viewModel.uiState.value.passphraseMismatch)
        assertFalse("mismatch gates submit", viewModel.uiState.value.canSubmitCloud)

        viewModel.onCloudPassphraseConfirmChange("secret")
        assertFalse(viewModel.uiState.value.passphraseMismatch)
        assertTrue(viewModel.uiState.value.canSubmitCloud)
    }

    @Test
    fun `create-account submit shows the data-safety gate before any network call`() = runBlocking {
        viewModel.onModeSelected(LoginMode.CLOUD)
        viewModel.onCloudTabChange(CloudTab.CREATE_ACCOUNT)
        fillValidCreateForm()

        viewModel.submit()
        advance()

        assertTrue("the gate dialog is the FIRST response to submit", viewModel.uiState.value.cloudCreateConfirmVisible)
        assertFalse("submitting waits for the confirm", viewModel.uiState.value.submitting)
        assertTrue("no auth call may run before the confirm", authCalls.isEmpty())

        // Confirm: only now does signUp run.
        viewModel.onCloudCreateConfirmed()
        advance()

        assertEquals(listOf("signUp"), authCalls)
        assertFalse(viewModel.uiState.value.cloudCreateConfirmVisible)
    }

    @Test
    fun `cancelling the create gate keeps the drafts and offers the gate again`() = runBlocking {
        viewModel.onModeSelected(LoginMode.CLOUD)
        viewModel.onCloudTabChange(CloudTab.CREATE_ACCOUNT)
        fillValidCreateForm()
        viewModel.submit()
        advance()
        assertTrue(viewModel.uiState.value.cloudCreateConfirmVisible)

        viewModel.onCloudCreateCancelled()
        advance()

        assertFalse(viewModel.uiState.value.cloudCreateConfirmVisible)
        assertEquals("username draft survives the cancel", "nick", viewModel.uiState.value.cloudUsername)
        assertEquals("passphrase draft survives the cancel", "secret", viewModel.uiState.value.cloudPassphrase)
        assertEquals("confirm draft survives the cancel", "secret", viewModel.uiState.value.cloudPassphraseConfirm)
        assertTrue("the form is still submittable after Go back", viewModel.uiState.value.canSubmitCloud)
        assertTrue("cancel must not have touched the network", authCalls.isEmpty())

        // Re-submitting re-gates: the warning is shown every time, not once per session.
        viewModel.submit()
        advance()
        assertTrue(viewModel.uiState.value.cloudCreateConfirmVisible)
        assertTrue(authCalls.isEmpty())
    }

    @Test
    fun `sign-in is not gated by the create confirm`() = runBlocking {
        viewModel.onModeSelected(LoginMode.CLOUD) // default tab is SIGN_IN
        viewModel.onCloudUsernameChange("nick")
        viewModel.onCloudPasswordChange("pw")
        viewModel.onCloudPassphraseChange("secret")
        assertTrue(viewModel.uiState.value.canSubmitCloud)

        viewModel.submit()
        advance()

        assertFalse("sign-in never shows the create gate", viewModel.uiState.value.cloudCreateConfirmVisible)
        assertEquals(listOf("signIn"), authCalls)
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

    private fun fillValidCreateForm(vm: LoginViewModel = viewModel) {
        vm.onCloudUsernameChange("nick")
        vm.onCloudPasswordChange("pw")
        vm.onCloudPassphraseChange("secret")
        vm.onCloudPassphraseConfirmChange("secret")
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
        vm.onModeSelected(LoginMode.CLOUD)
        vm.onCloudTabChange(CloudTab.CREATE_ACCOUNT)
        fillValidCreateForm(vm)
        vm.submit()
        advance()
        vm.onCloudCreateConfirmed()
        advance()
        collector.cancel()

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
        vm.onModeSelected(LoginMode.CLOUD)
        vm.onCloudTabChange(CloudTab.CREATE_ACCOUNT)
        fillValidCreateForm(vm)
        vm.submit()
        advance()
        vm.onCloudCreateConfirmed()
        advance()

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
        vm.onModeSelected(LoginMode.CLOUD)
        vm.onCloudTabChange(CloudTab.CREATE_ACCOUNT)
        fillValidCreateForm(vm)
        vm.submit()
        advance()
        vm.onCloudCreateConfirmed()
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

