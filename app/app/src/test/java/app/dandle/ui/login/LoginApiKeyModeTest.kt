package app.dandle.ui.login

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import app.dandle.data.auth.LoginFlow
import app.dandle.data.prefs.SessionStore
import app.dandle.domain.Clock
import app.dandle.domain.ServerClock
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * T8: the "Advanced: paste an API key" login path at the ViewModel level — gating, submit
 * routing (no username/password needed), and the session landing with the pasted key.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LoginApiKeyModeTest {

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
                    request.requestUrl!!.encodedPath == "/api/children/" ->
                        if (request.getHeader("Authorization") == "Token GOODKEY") {
                            MockResponse().setResponseCode(200).setBody(
                                """{"count":1,"next":null,"previous":null,"results":[
                                   {"id":1,"first_name":"Milo","last_name":"Rivera","birth_date":"2025-01-01","slug":"milo"}]}"""
                            )
                        } else {
                            MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid token."}""")
                        }
                    request.requestUrl!!.encodedPath == "/api/profile" -> MockResponse().setResponseCode(200).setBody(
                        """{"user":{"id":1,"username":"admin","first_name":"","last_name":"","email":"","is_staff":true},"language":"en-US","timezone":"UTC","api_key":"x"}"""
                    )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        sessionFile = java.nio.file.Files.createTempDirectory("apikey-test")
            .resolve("session.preferences_pb")
            .toString()
            .toPath()
        // ONE SessionStore instance (two DataStores on one file throw at first use).
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

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    /**
     * Progressive login step 1 → 2: probe the URL (any HTTP answer passes — the probe's
     * unauthenticated children call 401s against this dispatcher), acknowledge the http://
     * cleartext warning, and wait for the credentials step to open.
     */
    private fun awaitCredentialsStep() {
        viewModel.onContinueFromUrl()
        viewModel.onCleartextContinue() // http:// (the mock server) → cleartext warning on step 1
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.uiState.value.step != LoginStep.CREDENTIALS &&
            System.currentTimeMillis() < deadline
        ) {
            testDispatcher.scheduler.advanceUntilIdle()
            org.robolectric.Robolectric.flushForegroundThreadScheduler()
            Thread.sleep(25)
        }
        assertEquals(
            "the URL probe must reach the mock server and open the credentials step",
            LoginStep.CREDENTIALS,
            viewModel.uiState.value.step,
        )
    }

    @Test
    fun `paste-key mode gates submit on server plus key and signs in with the pasted key`() = runBlocking {
        viewModel.onServerUrlChange(server.url("/").toString())
        awaitCredentialsStep()
        viewModel.onApiKeyModeToggle()

        // Password fields are gone from the gating: no username/password needed here.
        assertFalse(viewModel.uiState.value.canSubmit)
        viewModel.onApiKeyChange("GOODKEY")
        assertTrue(viewModel.uiState.value.canSubmit)

        // Submit routes through the token path (children validation) and signs in.
        var signedIn = false
        val collector = launch(Dispatchers.Unconfined) {
            viewModel.events.collect { if (it is LoginEvent.SignedIn) signedIn = true }
        }
        // Cleartext was acknowledged at step 1, so submit goes straight to the token login.
        viewModel.submit()
        // Real-time deadline (no suspension in the loop body — withTimeout could never fire).
        val deadline = System.currentTimeMillis() + 10_000
        while (!signedIn && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            org.robolectric.Robolectric.flushForegroundThreadScheduler()
            Thread.sleep(25)
        }
        collector.cancel()
        assertTrue("paste-key submit must sign in", signedIn)

        val session = sessionStore.sessionFlow.first()
        assertEquals("GOODKEY", session.apiKey)
        assertTrue(session.isLoggedIn)
    }

    @Test
    fun `rejected key surfaces the bad-credentials banner`() = runBlocking {
        viewModel.onServerUrlChange(server.url("/").toString())
        awaitCredentialsStep()
        viewModel.onApiKeyModeToggle()
        viewModel.onApiKeyChange("WRONGKEY")
        viewModel.submit()
        val bannerDeadline = System.currentTimeMillis() + 10_000
        while (viewModel.uiState.value.banner == null && System.currentTimeMillis() < bannerDeadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            org.robolectric.Robolectric.flushForegroundThreadScheduler()
            Thread.sleep(25)
        }
        assertEquals(LoginBanner.BAD_CREDENTIALS, viewModel.uiState.value.banner)
        assertFalse(viewModel.uiState.value.submitting)
    }
}
