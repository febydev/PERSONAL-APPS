package com.privatemediavault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.privatemediavault.VaultContainer
import com.privatemediavault.data.MediaItem
import com.privatemediavault.domain.model.SessionState
import com.privatemediavault.ui.auth.PinScreen
import com.privatemediavault.ui.settings.SettingsScreen
import com.privatemediavault.ui.vault.VaultGridScreen
import com.privatemediavault.ui.viewer.MediaViewerScreen
import com.privatemediavault.viewmodel.AuthViewModel
import com.privatemediavault.viewmodel.SettingsViewModel
import com.privatemediavault.viewmodel.VaultViewModel
import com.privatemediavault.viewmodel.ViewerViewModel

/** Navigation destinations for the single-Activity Compose graph. */
internal object VaultRoute {
    const val PIN = "pin"
    const val GRID = "grid"
    const val VIEWER = "viewer"
    const val SETTINGS = "settings"
}

/**
 * The single-Activity navigation host that wires every screen together:
 * [PinScreen] (auth), [VaultGridScreen], [MediaViewerScreen], and [SettingsScreen].
 *
 * The vault always starts locked, so the graph starts at [VaultRoute.PIN]. Two routing
 * rules enforce the session model from the requirements:
 *
 *  - **Unlock routes forward.** When the user authenticates, the PIN screen's `onUnlocked`
 *    callback navigates to the grid, clearing the PIN entry from the back stack so Back
 *    cannot return to it.
 *  - **Lock routes back to PIN.** A global observer of [SessionManager.sessionState] sends
 *    the user to the PIN screen the instant the session becomes
 *    [SessionState.Locked] — whether from backgrounding (auto-lock, Req 9.1/9.2), an
 *    explicit lock (Req 9.4), or an unblur/export denied because the session was locked
 *    (Req 5.4, 7.2, 11.2). The whole back stack is cleared so no authenticated screen is
 *    reachable while locked.
 *
 * The selected [MediaItem] is held here and passed to the per-item [ViewerViewModel] when
 * navigating into the viewer.
 *
 * @param container the application object graph supplying the shared session manager and
 *   the view-model factories.
 */
@Composable
fun VaultNavHost(
    container: VaultContainer,
    navController: NavHostController = rememberNavController(),
) {
    // The item currently open in the viewer; set when the user taps a grid cell.
    var selectedItem by remember { mutableStateOf<MediaItem?>(null) }

    // Lock-routing: any transition to Locked sends the user back to PIN entry and clears
    // the authenticated back stack (Req 5.4, 9.1, 9.2, 9.4).
    val sessionState by container.sessionManager.sessionState.collectAsState()
    LaunchedEffect(sessionState) {
        if (shouldRouteToPin(sessionState, navController.currentDestination?.route)) {
            navController.toPin()
        }
    }

    NavHost(navController = navController, startDestination = VaultRoute.PIN) {
        composable(VaultRoute.PIN) {
            val authViewModel: AuthViewModel = viewModel(factory = container.authViewModelFactory)
            PinScreen(
                viewModel = authViewModel,
                onUnlocked = {
                    navController.navigate(VaultRoute.GRID) {
                        popUpTo(VaultRoute.PIN) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(VaultRoute.GRID) {
            val vaultViewModel: VaultViewModel = viewModel(factory = container.vaultViewModelFactory)
            VaultGridScreen(
                viewModel = vaultViewModel,
                onOpenItem = { item ->
                    selectedItem = item
                    navController.navigate(VaultRoute.VIEWER) { launchSingleTop = true }
                },
                onOpenSettings = {
                    navController.navigate(VaultRoute.SETTINGS) { launchSingleTop = true }
                },
            )
        }

        composable(VaultRoute.VIEWER) {
            val item = selectedItem
            if (item == null) {
                // No item selected (e.g. process death restored us here): pop back to the grid.
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            val viewerViewModel: ViewerViewModel =
                viewModel(factory = container.viewerViewModelFactory(item))
            MediaViewerScreen(
                viewModel = viewerViewModel,
                onNavigateToPin = { navController.toPin() },
                onBack = { navController.popBackStack() },
            )
        }

        composable(VaultRoute.SETTINGS) {
            val settingsViewModel: SettingsViewModel =
                viewModel(factory = container.settingsViewModelFactory)
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateToPin = { navController.toPin() },
            )
        }
    }
}

/**
 * The locked-state routing decision used by [VaultNavHost]: the user must be sent back to
 * the PIN screen exactly when the session is [SessionState.Locked] and they are currently
 * on a known, non-PIN destination (Req 9.1, 9.2). A `null` [currentRoute] (graph not yet
 * laid out) and the case where the user is already on [VaultRoute.PIN] both yield `false`
 * so no redundant navigation is issued.
 *
 * Extracted as a pure function so the locked -> PIN decision is verifiable without driving
 * the full Compose navigation host.
 */
internal fun shouldRouteToPin(sessionState: SessionState, currentRoute: String?): Boolean =
    sessionState is SessionState.Locked && currentRoute != null && currentRoute != VaultRoute.PIN

/** Navigates to the PIN screen, clearing the entire back stack so nothing authenticated remains. */
private fun NavHostController.toPin() {
    navigate(VaultRoute.PIN) {
        popUpTo(graph.findStartDestination().id) { inclusive = true }
        launchSingleTop = true
    }
}
