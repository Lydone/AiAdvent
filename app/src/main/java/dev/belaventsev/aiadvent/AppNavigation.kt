package dev.belaventsev.aiadvent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun AppNavigation(vm: ChatViewModel = viewModel()) {
    val navController = rememberNavController()
    val uiState by vm.uiState.collectAsState()

    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            ChatScreen(
                vm = vm,
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(
                currentStrategy = uiState.strategy,
                onStrategyChanged = vm::setStrategy,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
