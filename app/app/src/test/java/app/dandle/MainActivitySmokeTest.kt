package app.dandle

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dandle.ui.TestTags
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Commit-1 smoke test: the app host boots, the theme applies, and the session-driven start
 * destination renders. With no stored session the app opens on the Login screen.
 * Runs on Robolectric — no emulator needed.
 *
 * Updated for progressive disclosure (owner directive): step 1 is the server URL + a Continue
 * probe (`login/continue`); the credentials form and the Sign in button (`login/submit`) only
 * exist once the URL verified. Step 2 is reached here against a MockWebServer — `verifyUrl`
 * counts ANY HTTP answer (even the 401 an unauthenticated probe gets) as "reached".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** Only the step-2 test needs a reachable server; started per test, closed in tearDown. */
    private var server: MockWebServer? = null

    @After
    fun tearDown() {
        server?.shutdown()
    }

    private fun awaitTag(tag: String, timeoutMillis: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `fresh install renders the login screen`() {
        awaitTag(TestTags.LOGIN)
        composeRule.onNodeWithTag(TestTags.LOGIN).assertIsDisplayed()

        // Brief 04: the picker is the landing; picking the server card reveals step 1 of the
        // pinned progressive flow (URL probe), and credentials stay withheld until verified.
        // The three cards exist (the third can sit below the fold on tiny test viewports).
        composeRule.onNodeWithTag(TestTags.LOGIN_MODE_DEVICE).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.LOGIN_MODE_SERVER).performScrollTo()
        composeRule.onNodeWithTag(TestTags.LOGIN_MODE_SERVER).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.LOGIN_MODE_CLOUD).performScrollTo()
        composeRule.onNodeWithTag(TestTags.LOGIN_MODE_CLOUD).assertExists()
        composeRule.onNodeWithTag(TestTags.LOGIN_MODE_SERVER).performClick()
        awaitTag(TestTags.LOGIN_SERVER_URL)
        composeRule.onNodeWithTag(TestTags.LOGIN_SERVER_URL).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.LOGIN_CONTINUE).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.LOGIN_SUBMIT).assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.LOGIN_USERNAME).assertDoesNotExist()
    }

    @Test
    fun `submit stays disabled until all fields are filled`() {
        val mock = startProbeServer()
        awaitTag(TestTags.LOGIN)

        // Picker -> Step 1 -> 2: verify the http:// mock URL — cleartext warning, then creds.
        composeRule.onNodeWithTag(TestTags.LOGIN_MODE_SERVER).performClick()
        awaitTag(TestTags.LOGIN_SERVER_URL)
        composeRule.onNodeWithTag(TestTags.LOGIN_SERVER_URL)
            .performTextInput(mock.url("/").toString())
        composeRule.onNodeWithTag(TestTags.LOGIN_CONTINUE).performClick()
        awaitTag(TestTags.LOGIN_CLEARTEXT_DIALOG)
        composeRule.onNodeWithTag(TestTags.LOGIN_CLEARTEXT_CONTINUE).performClick()
        awaitTag(TestTags.LOGIN_USERNAME, timeoutMillis = 15_000)

        // Step 2: Sign in now exists, still disabled, and names what is missing (T10).
        composeRule.onNodeWithTag(TestTags.LOGIN_SUBMIT).assertIsNotEnabled()
        composeRule.onNodeWithText("Enter your username and password", substring = true)
            .assertExists()

        // Filling every credential lifts the gate (nothing is submitted here).
        composeRule.onNodeWithTag(TestTags.LOGIN_USERNAME).performTextInput("user")
        composeRule.onNodeWithTag(TestTags.LOGIN_PASSWORD).performTextInput("secret")
        composeRule.onNodeWithTag(TestTags.LOGIN_SUBMIT).assertIsEnabled()
        composeRule.onNodeWithText("Sign in", substring = true).assertExists()
    }

    /** Probe dispatcher: ANY HTTP answer counts as a reachable server (401 on the probe). */
    private fun startProbeServer(): MockWebServer {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(401).setBody("""{"detail":"Authentication required."}""")
        }
        s.start()
        server = s
        return s
    }
}
