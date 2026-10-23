package app.dandle

import android.content.Intent
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
import app.dandle.data.prefs.Session
import app.dandle.ui.AppNavHost
import app.dandle.ui.Routes
import app.dandle.ui.login.LoginViewModel
import app.dandle.ui.theme.BabyBuddyNextTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        openPanelFromIntent(intent)
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
        app.dandle.log.BbLog.d(
            app.dandle.log.BbLog.TAG_START,
            "activity onCreate done (setContent composed)",
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: widget deep links reuse this instance.
        openPanelFromIntent(intent)
    }

    /** Parses the widget deep link into the deep-link channel (no-op when absent). */
    private fun openPanelFromIntent(intent: Intent?) {
        val panelType = intent?.getStringExtra(app.dandle.widget.WidgetActions.EXTRA_OPEN_PANEL)
            ?.let { raw -> runCatching { app.dandle.domain.model.EventType.valueOf(raw) }.getOrNull() }
        if (panelType != null) {
            app.dandle.widget.WidgetDeepLink.requestPanel(panelType)
        }
        val stopType = intent?.getStringExtra(app.dandle.widget.WidgetActions.EXTRA_STOP_TIMER)
            ?.let { raw -> runCatching { app.dandle.domain.model.EventType.valueOf(raw) }.getOrNull() }
        if (stopType != null) {
            app.dandle.widget.WidgetDeepLink.requestStop(stopType)
        }
    }
}

@Composable
private fun RootNavigation(container: app.dandle.data.AppContainer) {
    val session by container.sessionStore.sessionFlow.collectAsStateWithLifecycle(initialValue = null)

    // Freeze the start destination on the first session emission so later session changes
    // (login, sign-out) navigate explicitly instead of recreating the NavHost.
    var initialStart by remember { mutableStateOf<String?>(null) }
    if (initialStart == null && session != null) {
        initialStart =
            if ((session as Session).isLoggedIn) Routes.DASHBOARD else Routes.LOGIN
        app.dandle.log.BbLog.d(
            app.dandle.log.BbLog.TAG_START,
            "start destination resolved: $initialStart",
        )
    }

    when (initialStart) {
        null -> Unit // first DataStore read still in flight; blank frame is fine (<50 ms)
        else -> AppNavHost(
            startDestination = initialStart!!,
            container = container,
        )
    }
}
