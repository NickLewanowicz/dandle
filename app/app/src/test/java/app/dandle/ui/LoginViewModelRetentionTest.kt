package app.dandle.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import app.dandle.App
import app.dandle.MainActivity
import app.dandle.data.prefs.SessionStore
import app.dandle.ui.login.LoginViewModel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regression test for the login dead-end (smoke test, fixed 2026-10): RootNavigation recomposes
 * on EVERY sessionStore emission — the server-clock interceptor writes an offset on the first
 * login response, and saveLogin writes again on success. The LoginViewModel used to be
 * constructed INLINE in that composable, so each emission silently replaced it: the screen kept
 * rendering the original ViewModel (the state collector is remembered per composition position)
 * while clicks targeted the replacement — the completing login's SignedIn event was emitted to
 * a disposed collector, zero subscribers, no replay — so navigation never happened and the user
 * stayed stranded on an idle login screen.
 *
 * The fix scopes the LoginViewModel to the Activity's ViewModelStore via viewModel(...). That is
 * structurally observable and race-free: the ViewModel must be REGISTERED in the store (inline
 * construction registers nothing) and must be the SAME instance across session emissions.
 *
 * Updated for progressive disclosure: the click path now drives BOTH steps — Continue probes
 * the URL against a MockWebServer (step 1), then username/password + Sign in complete a real
 * form login (step 2). The end assertion is the one the original bug broke: navigation to the
 * dashboard. The completing login fires exactly the session emissions that used to replace the
 * ViewModel, so the final assertSame checks it survived them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LoginViewModelRetentionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var server: MockWebServer

    private fun sessionStore(): SessionStore =
        (composeRule.activity.application as App).container.sessionStore

    /** What the production AppNavHost binding resolves to (same explicit owner + initializer). */
    private fun currentLoginViewModel(): LoginViewModel? {
        val owner = composeRule.activity as ViewModelStoreOwner
        return owner.viewModelStore.keys()
            .mapNotNull { owner.viewModelStore.get(it) }
            .filterIsInstance<LoginViewModel>()
            .firstOrNull()
    }

    /** Full form-login dance (csrf → POST 302+session → profile → children) + sync noise. */
    private fun startLoginServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/login/" && request.method == "GET" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Set-Cookie", "csrftoken=CSRF123; Path=/")
                        .setBody(
                            """<html><form><input type="hidden" name="csrfmiddlewaretoken" value="TOKEN789"><input name="next"></form></html>"""
                        )
                    path == "/login/" && request.method == "POST" -> MockResponse()
                        .setResponseCode(302)
                        .setHeader("Set-Cookie", "csrftoken=CSRF123; Path=/")
                        .setHeader("Set-Cookie", "sessionid=SESS456; Path=/")
                        .setBody("")
                    path == "/api/profile" -> MockResponse().setResponseCode(200).setBody(
                        """{"user":{"id":1,"username":"admin","first_name":"","last_name":"",
                           "email":"","is_staff":true},"language":"en-US","timezone":"UTC",
                           "api_key":"KEY789"}"""
                    )
                    path == "/api/children/" -> MockResponse().setResponseCode(200).setBody(
                        """{"count":1,"next":null,"previous":null,"results":[
                           {"id":1,"first_name":"Milo","last_name":"Rivera","birth_date":"2025-01-01","slug":"milo"}]}"""
                    )
                    // Dashboard sync noise after the login lands: empty pages keep it quiet.
                    else -> MockResponse().setResponseCode(200).setBody(
                        """{"count":0,"next":null,"previous":null,"results":[]}"""
                    )
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    private fun awaitTag(tag: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `login viewmodel is store-backed and survives session emissions`() {
        startLoginServer()
        awaitTag(TestTags.LOGIN)

        // THE fix: the ViewModel is registered in a ViewModelStore, never constructed inline.
        val bound = currentLoginViewModel()
        assertNotNull(
            "LoginViewModel must be store-backed (viewModel {}), not constructed inline",
            bound,
        )

        // Session writes like the ones that fire around a real login must not replace it.
        runBlocking { sessionStore().setServerOffsetMs(123_000L) }
        runBlocking { sessionStore().sessionFlow.first { it.serverOffsetMs == 123_000L } }
        runBlocking { sessionStore().saveLogin("http://stored.example.com", apiKey = "key", username = "user") }
        runBlocking { sessionStore().sessionFlow.first { it.serverUrl == "http://stored.example.com" } }
        composeRule.waitForIdle()

        assertSame("session emission replaced the LoginViewModel", bound, currentLoginViewModel())

        // And the click path still drives the rendered state — through BOTH progressive steps,
        // ending in the navigation the original dead-end bug swallowed.
        composeRule.onNodeWithTag(TestTags.LOGIN_SERVER_URL)
            .performTextClearance() // guard: session prefill may have landed after the init read
        composeRule.onNodeWithTag(TestTags.LOGIN_SERVER_URL)
            .performTextInput(server.url("/").toString())
        composeRule.onNodeWithTag(TestTags.LOGIN_CONTINUE).performClick()

        // http:// (the mock) raises the cleartext warning on step 1 — acknowledge it.
        awaitTag(TestTags.LOGIN_CLEARTEXT_DIALOG)
        composeRule.onNodeWithText("Unencrypted connection").assertExists()
        composeRule.onNodeWithTag(TestTags.LOGIN_CLEARTEXT_CONTINUE).performClick()

        // Step 2 renders once the probe answers (any HTTP code counts as reached).
        awaitTag(TestTags.LOGIN_SERVER_VERIFIED)
        composeRule.onNodeWithTag(TestTags.LOGIN_USERNAME).performTextInput("admin")
        composeRule.onNodeWithTag(TestTags.LOGIN_PASSWORD).performTextInput("admin")
        composeRule.onNodeWithTag(TestTags.LOGIN_SUBMIT).performClick()

        // The completing login emits SignedIn to the CURRENTLY RENDERED collector — the exact
        // emissions that used to orphan the ViewModel — and must navigate to the dashboard.
        // (The dashboard tag is on nested containers, so existence, not uniqueness, is asserted.)
        awaitTag(TestTags.DASHBOARD)

        // The Activity-scoped ViewModel survived the login's session emissions and the hop.
        assertSame("login/hop replaced the LoginViewModel", bound, currentLoginViewModel())
    }
}
