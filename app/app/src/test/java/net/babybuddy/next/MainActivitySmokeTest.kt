package net.babybuddy.next

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.babybuddy.next.ui.TestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Commit-1 smoke test: the app host boots, the theme applies, and the session-driven start
 * destination renders. With no stored session the app opens on the Login screen.
 * Runs on Robolectric — no emulator needed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `fresh install renders the login screen`() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag(TestTags.LOGIN))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TestTags.LOGIN).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.LOGIN_SERVER_URL).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.LOGIN_SUBMIT).assertIsDisplayed()
    }

    @Test
    fun `submit stays disabled until all fields are filled`() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag(TestTags.LOGIN))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TestTags.LOGIN_SUBMIT)
            .assertExists()
        // T10: the disabled button says WHAT is missing instead of a bare "Sign in".
        composeRule.onNodeWithText("Enter the server URL, username, and password", substring = true)
            .assertExists()
    }
}
