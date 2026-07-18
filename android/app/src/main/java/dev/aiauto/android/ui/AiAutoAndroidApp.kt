package dev.aiauto.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

import dev.aiauto.android.provider.ProviderConfigRepository
import dev.aiauto.android.ui.home.HomeScreen
import dev.aiauto.android.ui.placeholder.RecordingScreen
import dev.aiauto.android.ui.placeholder.TaskScreen
import dev.aiauto.android.ui.provider.ProviderScreen

private object Route {
    const val HOME = "home"
    const val PROVIDER = "provider"
    const val TASK = "task"
    const val RECORDING = "recording"
}

@Composable
fun AiAutoAndroidApp(providerRepository: ProviderConfigRepository) {
    val navController = rememberNavController()
    val initialProvider = remember(providerRepository) { providerRepository.load() }
    var providerReady by remember { mutableStateOf(initialProvider.hasApiKey) }

    NavHost(
        navController = navController,
        startDestination = Route.HOME,
    ) {
        composable(Route.HOME) {
            HomeScreen(
                providerReady = providerReady,
                onProviderClick = { navController.navigate(Route.PROVIDER) },
                onTaskClick = { navController.navigate(Route.TASK) },
                onRecordingClick = { navController.navigate(Route.RECORDING) },
            )
        }
        composable(Route.PROVIDER) {
            ProviderScreen(
                repository = providerRepository,
                onBack = navController::popBackStack,
                onProviderStateChanged = { providerReady = it },
            )
        }
        composable(Route.TASK) {
            TaskScreen(onBack = navController::popBackStack)
        }
        composable(Route.RECORDING) {
            RecordingScreen(onBack = navController::popBackStack)
        }
    }
}
