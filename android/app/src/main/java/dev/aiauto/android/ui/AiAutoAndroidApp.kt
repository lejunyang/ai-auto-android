package dev.aiauto.android.ui

/**
 * 功能用途：组织 C 端四 tab 导航（助理、技能、记录、我的）与子页面路由，
 * 统一管理 Provider、无障碍、桌面桥、录制脚本存储等共享依赖的注入。
 */

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

import dev.aiauto.android.accessibility.settings.AccessibilityServiceStatus
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.automation.session.AndroidAutomationSessionFactory
import dev.aiauto.android.bridge.DesktopBridgeController
import dev.aiauto.android.provider.ProviderConfigRepository
import dev.aiauto.android.ui.accessibility.AccessibilityScreen
import dev.aiauto.android.ui.bridge.DesktopBridgeScreen
import dev.aiauto.android.ui.bridge.lan.LanPairingHost
import dev.aiauto.android.ui.bridge.lan.LanPairingViewModel
import dev.aiauto.android.ui.components.AppBottomNavigation
import dev.aiauto.android.ui.components.AppTab
import dev.aiauto.android.ui.history.HistoryScreen
import dev.aiauto.android.ui.home.AssistantScreen
import dev.aiauto.android.ui.provider.ProviderScreen
import dev.aiauto.android.ui.recording.RecordingHost
import dev.aiauto.android.ui.recording.RecordingViewModel
import dev.aiauto.android.ui.session.SessionScreen
import dev.aiauto.android.ui.session.SessionViewModel
import dev.aiauto.android.ui.settings.SettingsScreen

/** 路由常量：四个主 tab 为根路由，其余为全屏子页面。 */
private object Route {
    const val ASSISTANT = "assistant"
    const val SKILLS = "skills"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val SESSION = "session?task={task}"
    const val RECORDING = "recording?scriptId={scriptId}&startRecording={startRecording}"
    const val PROVIDER = "provider"
    const val ACCESSIBILITY = "accessibility"
    const val BRIDGE = "bridge"
    const val LAN_PAIRING = "lan-pairing"

    fun session(task: String = ""): String =
        "session?task=$task"

    fun recording(
        scriptId: String? = null,
        startRecording: Boolean = false,
    ): String =
        "recording?scriptId=${scriptId ?: ""}&startRecording=$startRecording"
}

/** 底部导航可见的根路由集合。 */
private val TAB_ROUTES = setOf(
    Route.ASSISTANT,
    Route.SKILLS,
    Route.HISTORY,
    Route.SETTINGS,
)

@Composable
fun AiAutoAndroidApp(
    providerRepository: ProviderConfigRepository,
    accessibilityRepository: AccessibilitySettingsRepository,
    bridgeController: DesktopBridgeController,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 共享只读状态：Provider 是否已配置、无障碍是否已配置并启用。
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
    val targetPackages = remember(accessibilityRepository) {
        accessibilityRepository.load().targetPackages
    }

    // 会话工厂与录制脚本存储在导航层共享，避免每个页面重复创建。
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
    val recordingScriptStore = remember(context) {
        RecordingScriptStore.from(context)
    }

    val bridgeState by bridgeController.state.collectAsStateWithLifecycle()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in TAB_ROUTES

    // 页面恢复时重新读取无障碍状态，确保从系统设置返回后状态同步。
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

    androidx.compose.material3.Scaffold(
        bottomBar = {
            if (showBottomBar) {
                AppBottomNavigation(
                    currentRoute = currentRoute,
                    onTabSelected = { tab ->
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Route.ASSISTANT,
            modifier = Modifier.padding(padding),
        ) {
            // 助理：任务输入、常用技能、最近活动、就绪状态。
            composable(Route.ASSISTANT) {
                AssistantScreen(
                    providerReady = providerReady,
                    accessibilityConfigured = accessibilityConfigured,
                    accessibilityEnabled = accessibilityEnabled,
                    targetPackages = targetPackages,
                    scriptStore = recordingScriptStore,
                    onStartTask = { task ->
                        navController.navigate(Route.session(task))
                    },
                    onOpenSkill = { scriptId ->
                        navController.navigate(Route.recording(scriptId = scriptId))
                    },
                    onStartRecording = {
                        navController.navigate(Route.recording(startRecording = true))
                    },
                    onGoToSkills = {
                        navController.navigate(AppTab.SKILLS.route)
                    },
                    onGoToSettings = {
                        navController.navigate(AppTab.SETTINGS.route)
                    },
                )
            }

            // 技能：脚本列表、筛选、录制入口。
            composable(Route.SKILLS) {
                dev.aiauto.android.ui.skills.SkillsScreen(
                    scriptStore = recordingScriptStore,
                    onOpenSkill = { scriptId ->
                        navController.navigate(Route.recording(scriptId = scriptId))
                    },
                    onStartRecording = {
                        navController.navigate(Route.recording(startRecording = true))
                    },
                )
            }

            // 记录：已保存脚本与运行记录，空态明确。
            composable(Route.HISTORY) {
                HistoryScreen(
                    scriptStore = recordingScriptStore,
                    onOpenScript = { scriptId ->
                        navController.navigate(Route.recording(scriptId = scriptId))
                    },
                )
            }

            // 我的：状态汇总与设置入口。
            composable(Route.SETTINGS) {
                SettingsScreen(
                    providerReady = providerReady,
                    accessibilityConfigured = accessibilityConfigured,
                    accessibilityEnabled = accessibilityEnabled,
                    bridgeState = bridgeState,
                    targetPackages = targetPackages,
                    onProviderClick = { navController.navigate(Route.PROVIDER) },
                    onAccessibilityClick = { navController.navigate(Route.ACCESSIBILITY) },
                    onBridgeClick = { navController.navigate(Route.BRIDGE) },
                )
            }

            // 会话详情：真实 AI 任务执行状态机。
            composable(
                route = Route.SESSION,
                arguments = listOf(
                    navArgument("task") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                val initialTask = backStackEntry.arguments?.getString("task").orEmpty()
                val sessionViewModel: SessionViewModel = viewModel(
                    factory = SessionViewModel.factory(sessionFactory),
                )
                SessionScreen(
                    viewModel = sessionViewModel,
                    initialTask = initialTask,
                    onBack = navController::popBackStack,
                )
            }

            // 录制流程：复用现有 RecordingHost，支持从技能页直接进入详情或新建录制。
            composable(
                route = Route.RECORDING,
                arguments = listOf(
                    navArgument("scriptId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("startRecording") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { backStackEntry ->
                val scriptId = backStackEntry.arguments?.getString("scriptId")
                    ?.takeIf(String::isNotBlank)
                val startRecording =
                    backStackEntry.arguments?.getBoolean("startRecording") == true
                val recordingViewModel: RecordingViewModel = viewModel(
                    factory = RecordingViewModel.factory(context),
                )
                RecordingHost(
                    viewModel = recordingViewModel,
                    onBack = navController::popBackStack,
                    initialScriptId = scriptId,
                    startRecordingImmediately = startRecording,
                )
            }

            // AI 模型配置（现有 ProviderScreen）。
            composable(Route.PROVIDER) {
                ProviderScreen(
                    repository = providerRepository,
                    onBack = navController::popBackStack,
                    onProviderStateChanged = { providerReady = it },
                )
            }

            // 应用与权限（现有 AccessibilityScreen）。
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

            // 连接电脑（现有 DesktopBridgeScreen）。
            composable(Route.BRIDGE) {
                DesktopBridgeScreen(
                    controller = bridgeController,
                    onBack = navController::popBackStack,
                    onLanPairing = { navController.navigate(Route.LAN_PAIRING) },
                )
            }

            // LAN 配对（现有 LanPairingHost）。
            composable(Route.LAN_PAIRING) {
                val pairingViewModel: LanPairingViewModel = viewModel(
                    factory = LanPairingViewModel.factory(context),
                )
                LanPairingHost(
                    viewModel = pairingViewModel,
                    onBack = navController::popBackStack,
                )
            }
        }
    }
}
