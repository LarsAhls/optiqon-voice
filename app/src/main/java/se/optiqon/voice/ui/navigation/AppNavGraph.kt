package se.optiqon.voice.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import se.optiqon.voice.ui.access.AccountScreen
import se.optiqon.voice.ui.access.AccountUiState
import se.optiqon.voice.ui.access.AccountViewModel
import se.optiqon.voice.ui.home.HomeScreen
import se.optiqon.voice.ui.onboarding.OnboardingScreen
import se.optiqon.voice.ui.profiles.ProfilesScreen
import se.optiqon.voice.ui.settings.SettingsScreen
import se.optiqon.voice.ui.theme.AppIcons

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"

    const val HOME = "home"
    const val PROFILES = "profiles"
    const val SETTINGS = "settings"
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.HOME, "Transcriptions", AppIcons.Description),
    BottomDestination(Routes.PROFILES, "Profiles", AppIcons.Tune),
    BottomDestination(Routes.SETTINGS, "Settings", Icons.Default.Settings)
)

@Composable
fun AppNavGraph(rootViewModel: RootViewModel = hiltViewModel()) {
    val resolved by rootViewModel.startDestination.collectAsStateWithLifecycle()

    // The graph is built once, from the first answer. Later changes to the flag are the
    // result of finishing onboarding, which navigates on its own; rebuilding the graph
    // underneath that would throw the back stack away mid-transition.
    var startDestination by remember { mutableStateOf<StartDestination?>(null) }
    LaunchedEffect(resolved) {
        if (startDestination == null) startDestination = resolved
    }

    val start = startDestination
    if (start == null) {
        // One frame or two of the same colour the window already is, rather than a flash of
        // the wrong screen.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (start == StartDestination.ONBOARDING) Routes.ONBOARDING else Routes.MAIN
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.MAIN) {
            // Registration gates the whole app, not just dictation, so the shell is only
            // composed for an approved account.
            AccountGate { MainShell() }
        }
    }
}

/**
 * Shows the account screen until the server has said this account is approved.
 *
 * The gate is drawn from [AccountViewModel.state], which is derived from the stored verdict
 * rather than from the fact that somebody managed to sign in. A signed-in account with no
 * decision yet sees the waiting screen, not the app.
 */
@Composable
private fun AccountGate(content: @Composable () -> Unit) {
    val viewModel: AccountViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state is AccountUiState.Approved) content() else AccountScreen(viewModel)
}

@Composable
private fun MainShell() {
    val navController = rememberNavController()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                bottomDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME
        ) {
            composable(Routes.HOME) {
                HomeScreen(outerPadding = padding)
            }
            composable(Routes.PROFILES) {
                ProfilesScreen(outerPadding = padding)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(outerPadding = padding)
            }
        }
    }
}
