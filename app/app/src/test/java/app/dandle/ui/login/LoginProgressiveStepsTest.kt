package app.dandle.ui.login

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import app.dandle.data.auth.LoginFlow
import app.dandle.data.prefs.SessionStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Progressive disclosure login (owner directive): step 1 verifies the server URL (any HTTP
 * answer — even a 401 — counts as reached; only connection failure = Unreachable keeps you on
 * step 1), step 2 asks for credentials, and submit() is a no-op until step 2.
 *
 * Drives the real LoginViewModel against MockWebServer (the probe is a real OkHttp call),
 * matching LoginApiKeyModeTest's conventions: Main = StandardTestDispatcher, real-time poll
 * loops that advance the scheduler and flush Robolectric's foreground thread.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LoginProgressiveStepsTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var sessionFile: okio.Path
    private lateinit var viewModel: LoginViewModel
    private lateinit var sessionStore: SessionStore

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                // The verifyUrl probe hits the children list unauthenticated: a 401 is still
                // "reached" — auth is exactly what step 2 is for.
                request.requestUrl!!.encodedPath == "/api/children/" ->
                    MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid token."}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        sessionFile = java.nio.file.Files.createTempDirectory("progressive-test")
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
        )
    }

    /** Brief 04: the picker is the landing; SERVER tests pick it first. */
    private fun pickServer() {
        assertEquals(LoginStep.MODE, viewModel.uiState.value.step)
        viewModel.onModeSelected(LoginMode.SERVER)
        assertEquals(LoginStep.URL, viewModel.uiState.value.step)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    /** Advances the scheduler until [pred] holds (probe runs on the real IO pool). */
    private fun awaitState(what: String, pred: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!pred() && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            org.robolectric.Robolectric.flushForegroundThreadScheduler()
            Thread.sleep(25)
        }
        assertTrue("timed out waiting for $what (state=${viewModel.uiState.value})", pred())
    }

    /** Step 1 → 2 against the mock: Continue, acknowledge the http:// cleartext warning. */
    private fun continueFromUrlToCredentials() {
        viewModel.onContinueFromUrl()
        viewModel.onCleartextContinue() // http:// (the mock server) → cleartext warning on step 1
        awaitState("step CREDENTIALS") { viewModel.uiState.value.step == LoginStep.CREDENTIALS }
    }

    @Test
    fun `a reachable url advances to the credentials step`() = runBlocking {
        // Fresh state: the picker; picking SERVER lands on step 1 of the pinned flow.
        pickServer()
        assertFalse(viewModel.uiState.value.canContinue)
        viewModel.onServerUrlChange(server.url("/").toString())
        assertTrue(viewModel.uiState.value.canContinue)

        continueFromUrlToCredentials()

        val state = viewModel.uiState.value
        assertEquals(LoginStep.CREDENTIALS, state.step)
        assertFalse("the verifying spinner must clear", state.verifying)
        assertNull("reaching step 2 must not leave a banner", state.banner)
        assertFalse(state.cleartextDialogVisible)
    }

    @Test
    fun `an unreachable url stays on the url step with the unreachable banner`() = runBlocking {
        pickServer()
        val url = server.url("/").toString()
        server.shutdown() // connection refused — LoginFlowTest's double-shutdown precedent

        viewModel.onServerUrlChange(url)
        viewModel.onContinueFromUrl()
        viewModel.onCleartextContinue()

        awaitState("the UNREACHABLE banner") { viewModel.uiState.value.banner == LoginBanner.UNREACHABLE }
        val state = viewModel.uiState.value
        assertEquals("a failed probe must keep the user on step 1", LoginStep.URL, state.step)
        assertFalse("the verifying spinner must clear", state.verifying)
        assertFalse(state.cleartextDialogVisible)
        assertFalse(state.canSubmit)
    }

    @Test
    fun `editing the server url on the credentials step keeps the state consistent`() = runBlocking {
        pickServer()
        viewModel.onServerUrlChange(server.url("/").toString())
        continueFromUrlToCredentials()

        // The user edits the verified URL while on step 2 (never contacted here).
        viewModel.onServerUrlChange("http://other.example.com")

        val state = viewModel.uiState.value
        assertEquals("editing must not strand the user mid-flow", LoginStep.CREDENTIALS, state.step)
        assertFalse(state.verifying)
        assertFalse(state.cleartextDialogVisible)
        assertNull("editing the URL clears any stale banner", state.banner)
        assertEquals("http://other.example.com", state.serverUrl)
        assertTrue("the probe button gating must track the edited URL", state.canContinue)

        // Submit gating follows the edited state instead of freezing.
        viewModel.onUsernameChange("user")
        viewModel.onPasswordChange("secret")
        assertTrue(viewModel.uiState.value.canSubmit)
        viewModel.onServerUrlChange("")
        assertFalse("a blank URL must re-gate submit", viewModel.uiState.value.canSubmit)

        // And the documented way back (the verified chip's "Change") re-opens step 1 with the
        // typed URL preserved, so re-verifying is one Continue away — not a dead end either.
        viewModel.onServerUrlChange("http://other.example.com")
        viewModel.onBackToUrl()
        assertEquals(LoginStep.URL, viewModel.uiState.value.step)
        assertEquals("http://other.example.com", viewModel.uiState.value.serverUrl)
        assertFalse(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `submit before the credentials step is a no op`() = runBlocking {
        // Everything filled: only the step gating may block — and it must.
        pickServer()
        viewModel.onServerUrlChange(server.url("/").toString())
        viewModel.onUsernameChange("user")
        viewModel.onPasswordChange("secret")
        viewModel.onApiKeyChange("GOODKEY")
        assertFalse("step URL must gate submit even with every field filled", viewModel.uiState.value.canSubmit)

        var signedIn = false
        val collector = launch(Dispatchers.Unconfined) {
            viewModel.events.collect { if (it is LoginEvent.SignedIn) signedIn = true }
        }

        viewModel.submit()
        // A no-op must be an observable absence: drain briefly, then assert nothing moved.
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            org.robolectric.Robolectric.flushForegroundThreadScheduler()
            Thread.sleep(25)
        }
        collector.cancel()

        assertFalse("no sign-in event may fire from step 1", signedIn)
        val state = viewModel.uiState.value
        assertEquals(LoginStep.URL, state.step)
        assertFalse(state.submitting)
        assertFalse(state.verifying)
        assertFalse(state.cleartextDialogVisible)
        assertNull(state.banner)
        assertEquals(
            "submit() on step 1 must not touch the network",
            0,
            server.requestCount,
        )
    }
}
