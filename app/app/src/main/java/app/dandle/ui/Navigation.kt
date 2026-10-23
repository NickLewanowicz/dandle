package app.dandle.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.dandle.data.AppContainer
import app.dandle.ui.dashboard.DashboardScreen
import app.dandle.ui.login.LoginScreen
import app.dandle.ui.login.LoginViewModel

/** Route names for the beta's two top-level destinations. */
object Routes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
}

/**
 * Beta scaffold: exactly two destinations, login <-> dashboard (spec §1/§2).
 * The start destination is session-driven (MainActivity decides before composing this).
 *
 * ViewModels are store-backed (viewModel with an explicit owner): they MUST survive
 * recompositions of this NavHost — RootNavigation recomposes on every sessionStore emission
 * (server-clock offset writes fire on every server response), and an inline-constructed
 * ViewModel would be swapped mid-login, orphaning the in-flight coroutine and its SignedIn
 * event (the login dead-end bug, fixed 2026-10). The LoginViewModel is scoped to the Activity
 * so it also survives login <-> dashboard hops (session-expired re-auth, spec §6.3.6).
 */
@Composable
fun AppNavHost(
    startDestination: String,
    container: AppContainer,
) {
    val navController = rememberNavController()
    Surface(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = startDestination) {
            composable(Routes.LOGIN) {
                // Activity-scoped (explicit owner): RootNavigation recomposes on every
                // sessionStore emission — the ViewModel must never be re-created by that.
                val loginViewModel: LoginViewModel = viewModel(
                    viewModelStoreOwner = LocalContext.current as ViewModelStoreOwner,
                ) {
                    LoginViewModel(
                        loginFlow = container.loginFlow,
                        sessionStore = container.sessionStore,
                    )
                }
                LoginScreen(
                    onSignedIn = {
                        navController.navigate(Routes.DASHBOARD) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                    viewModel = loginViewModel,
                )
            }
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    container = container,
                    onSignedOut = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(Routes.DASHBOARD) { inclusive = true }
                        }
                    },
                    // Session expired mid-sync (spec §6.3.6): back to login (fields pre-filled);
                    // the outbox resumes automatically after a successful sign-in.
                    onSignIn = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(Routes.DASHBOARD) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}
