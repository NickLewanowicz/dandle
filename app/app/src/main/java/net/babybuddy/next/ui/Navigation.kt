package net.babybuddy.next.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.babybuddy.next.data.AppContainer
import net.babybuddy.next.ui.dashboard.DashboardScreen
import net.babybuddy.next.ui.login.LoginScreen
import net.babybuddy.next.ui.login.LoginViewModel

/** Route names for the beta's two top-level destinations. */
object Routes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
}

/**
 * Beta scaffold: exactly two destinations, login <-> dashboard (spec §1/§2).
 * The start destination is session-driven (MainActivity decides before composing this).
 */
@Composable
fun AppNavHost(
    startDestination: String,
    container: AppContainer,
    loginViewModel: LoginViewModel,
) {
    val navController = rememberNavController()
    Surface(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = startDestination) {
            composable(Routes.LOGIN) {
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
