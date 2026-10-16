package net.babybuddy.next.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.babybuddy.next.ui.login.LoginScreen
import net.babybuddy.next.ui.login.LoginViewModel
import net.babybuddy.next.ui.theme.TimerCounterStyle

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
                DashboardPlaceholder()
            }
        }
    }
}

@Composable
private fun DashboardPlaceholder() {
    Text(
        text = "Baby Buddy Next",
        style = TimerCounterStyle,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
