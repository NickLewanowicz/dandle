package net.babybuddy.next

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.babybuddy.next.data.prefs.Session
import net.babybuddy.next.ui.AppNavHost
import net.babybuddy.next.ui.Routes
import net.babybuddy.next.ui.login.LoginViewModel
import net.babybuddy.next.ui.theme.BabyBuddyNextTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as App).container
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
                    RootNavigation(container = container)
                }
            }
        }
    }
}

@Composable
private fun RootNavigation(container: net.babybuddy.next.data.AppContainer) {
    val session by container.sessionStore.sessionFlow.collectAsStateWithLifecycle(initialValue = null)

    // Freeze the start destination on the first session emission so later session changes
    // (login, sign-out) navigate explicitly instead of recreating the NavHost.
    var initialStart by remember { mutableStateOf<String?>(null) }
    if (initialStart == null && session != null) {
        initialStart =
            if ((session as Session).isLoggedIn) Routes.DASHBOARD else Routes.LOGIN
    }

    when (initialStart) {
        null -> Unit // first DataStore read still in flight; blank frame is fine (<50 ms)
        else -> AppNavHost(
            startDestination = initialStart!!,
            loginViewModel = LoginViewModel(
                loginFlow = container.loginFlow,
                sessionStore = container.sessionStore,
            ),
        )
    }
}
