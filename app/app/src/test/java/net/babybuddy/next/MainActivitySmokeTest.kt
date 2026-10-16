package net.babybuddy.next

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Commit-1 smoke test: the app host boots, the theme applies, and the placeholder dashboard
 * renders its title. Runs on Robolectric — no emulator needed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardPlaceholderTitleIsDisplayed() {
        composeRule.onNodeWithText("Baby Buddy Next").assertIsDisplayed()
    }
}
