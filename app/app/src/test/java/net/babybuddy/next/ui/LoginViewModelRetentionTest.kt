package net.babybuddy.next.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.babybuddy.next.App
import net.babybuddy.next.MainActivity
import net.babybuddy.next.data.prefs.SessionStore
import net.babybuddy.next.ui.login.LoginViewModel
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
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LoginViewModelRetentionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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

    @Test
    fun `login viewmodel is store-backed and survives session emissions`() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasTestTag(TestTags.LOGIN_SERVER_URL))
                .fetchSemanticsNodes().isNotEmpty()
        }

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

        // And the click path still drives the rendered state: http:// URL → cleartext dialog.
        composeRule.onNodeWithTag(TestTags.LOGIN_SERVER_URL)
            .performTextInput("http://stored.example.com")
        composeRule.onNodeWithTag(TestTags.LOGIN_USERNAME).performTextInput("user")
        composeRule.onNodeWithTag(TestTags.LOGIN_PASSWORD).performTextInput("secret")
        composeRule.onNodeWithTag(TestTags.LOGIN_SUBMIT).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Unencrypted connection")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").assertExists()
    }
}
