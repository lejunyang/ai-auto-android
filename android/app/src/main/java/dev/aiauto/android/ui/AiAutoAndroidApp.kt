package dev.aiauto.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

import dev.aiauto.android.accessibility.settings.AccessibilityServiceStatus
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.automation.session.AndroidAutomationSessionFactory
import dev.aiauto.android.bridge.DesktopBridgeController
import dev.aiauto.android.provider.ProviderConfigRepository
import dev.aiauto.android.ui.accessibility.AccessibilityScreen
import dev.aiauto.android.ui.bridge.DesktopBridgeScreen
import dev.aiauto.android.ui.home.HomeScreen
import dev.aiauto.android.ui.placeholder.RecordingScreen
import dev.aiauto.android.ui.provider.ProviderScreen
import dev.aiauto.android.ui.session.SessionScreen
import dev.aiauto.android.ui.session.SessionViewModel

private object Route {
    const val HOME = "home"
    const val ACCESSIBILITY = "accessibility"
    const val PROVIDER = "provider"
    const val BRIDGE = "bridge"
    const val TASK = "task"
    const val RECORDING = "recording"
}

@Composable
fun AiAutoAndroidApp(
    providerRepository: ProviderConfigRepository,
    accessibilityRepository: AccessibilitySettingsRepository,
    bridgeController: DesktopBridgeController,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val initialProvider = remember(providerRepository) { providerRepository.load() }
    val initialAccessibility = remember(accessibilityRepository) {
        accessibilityRepository.load()
    }
    var providerReady by remember { mutableStateOf(initialProvider.hasApiKey) }
    var accessibilityConfigured by remember {
        mutableStateOf(initialAccessibility.isReady)
    }
    var accessibilityEnabled by remember {
        mutableStateOf(AccessibilityServiceStatus.isEnabled(context))
    }
    val sessionFactory = remember(
        context,
        providerRepository,
        accessibilityRepository,
    ) {
        AndroidAutomationSessionFactory(
            context = context,
            providerRepository = providerRepository,
            accessibilityRepository = accessibilityRepository,
        )
    }
    val bridgeState by bridgeController.state.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner, accessibilityRepository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityConfigured = accessibilityRepository.load().isReady
                accessibilityEnabled = AccessibilityServiceStatus.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    NavHost(
        navController = navController,
        startDestination = Route.HOME,
    ) {
        composable(Route.HOME) {
            HomeScreen(
                providerReady = providerReady,
                accessibilityConfigured = accessibilityConfigured,
                accessibilityEnabled = accessibilityEnabled,
                bridgeState = bridgeState,
                onAccessibilityClick = { navController.navigate(Route.ACCESSIBILITY) },
                onProviderClick = { navController.navigate(Route.PROVIDER) },
                onBridgeClick = { navController.navigate(Route.BRIDGE) },
                onTaskClick = { navController.navigate(Route.TASK) },
                onRecordingClick = { navController.navigate(Route.RECORDING) },
            )
        }
        composable(Route.ACCESSIBILITY) {
            AccessibilityScreen(
                repository = accessibilityRepository,
                serviceEnabled = accessibilityEnabled,
                onBack = navController::popBackStack,
                onSettingsChanged = {
                    accessibilityConfigured = it.isReady
                },
            )
        }
        composable(Route.PROVIDER) {
            ProviderScreen(
                repository = providerRepository,
                onBack = navController::popBackStack,
                onProviderStateChanged = { providerReady = it },
            )
        }
        composable(Route.BRIDGE) {
            DesktopBridgeScreen(
                controller = bridgeController,
                onBack = navController::popBackStack,
            )
        }
        composable(Route.TASK) {
            val sessionViewModel: SessionViewModel = viewModel(
                factory = SessionViewModel.factory(sessionFactory),
            )
            SessionScreen(
                viewModel = sessionViewModel,
                onBack = navController::popBackStack,
            )
        }
        composable(Route.RECORDING) {
            RecordingScreen(onBack = navController::popBackStack)
        }
    }
}
