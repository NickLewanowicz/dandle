package net.babybuddy.next

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import net.babybuddy.next.ui.AppNavHost
import net.babybuddy.next.ui.theme.BabyBuddyNextTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BabyBuddyNextTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        // QA automation selects nodes by resource-id; expose Compose test tags
                        // as resource ids in debug builds only (DECISION.md §3).
                        .then(
                            if (BuildConfig.DEBUG) {
                                Modifier.semantics { testTagsAsResourceId = true }
                            } else {
                                Modifier
                            }
                        )
                        .testTag("app_root"),
                ) {
                    AppNavHost()
                }
            }
        }
    }
}
