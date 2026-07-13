package com.routedns.routebot.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.routedns.routebot.ui.dashboard.DashboardScreen
import com.routedns.routebot.ui.dashboard.DashboardViewModel
import com.routedns.routebot.ui.settings.SettingsScreen
import com.routedns.routebot.ui.settings.SettingsViewModel
import com.routedns.routebot.ui.setup.SetupScreen
import com.routedns.routebot.ui.setup.SetupViewModel

object Routes {
    const val SETUP = "setup"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
}

@Composable
fun RouteBotNavHost() {
    val navController = rememberNavController()
    val setupVm: SetupViewModel = hiltViewModel()
    val registered by setupVm.isRegistered.collectAsState()

    val start = if (registered) Routes.DASHBOARD else Routes.SETUP

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.SETUP) {
            SetupScreen(
                viewModel = hiltViewModel(),
                onComplete = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                viewModel = hiltViewModel(),
                onSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() }
            )
        }
    }
}
