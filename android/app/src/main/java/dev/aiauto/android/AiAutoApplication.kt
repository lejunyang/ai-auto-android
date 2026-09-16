package dev.aiauto.android

/**
 * 功能用途：应用级 Application，负责初始化 SessionRuntime 等全局容器。
 *
 * 安全边界：
 * - SessionRuntime 在 Application.onCreate 中初始化（applicationContext 安全），
 *   不依赖 Activity 或 Service 首次启动，避免 Service 收到 ACTION_START 时 coordinator 未初始化。
 * - 初始化幂等：重复调用 SessionRuntime.initialize 不会覆盖已有实例。
 * - release 变体使用真实 AndroidAutomationSessionFactory 和 SessionHistoryStore；
 *   test 变体可通过 SessionRuntime.resetForTesting() 隔离。
 */

import android.app.Application
import android.content.Context.MODE_PRIVATE
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.automation.session.AndroidAutomationSessionFactory
import dev.aiauto.android.automation.session.coordinator.SessionRuntime
import dev.aiauto.android.automation.session.history.SessionHistoryStore
import dev.aiauto.android.provider.ProviderConfigRepository
import dev.aiauto.android.security.AndroidKeystoreSecretCipher

class AiAutoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 应用级初始化 SessionRuntime，确保 Service 启动前 coordinator 已就绪。
        // 使用 applicationContext，不与 Activity 生命周期绑定。
        val historyStore = SessionHistoryStore.from(applicationContext)
        val scriptStore = RecordingScriptStore.from(applicationContext)
        val providerRepository = ProviderConfigRepository(
            preferences = getSharedPreferences("provider-config", MODE_PRIVATE),
            secretCipher = AndroidKeystoreSecretCipher(),
        )
        val accessibilityRepository = AccessibilitySettingsRepository.from(applicationContext)
        val sessionFactory = AndroidAutomationSessionFactory(
            context = applicationContext,
            providerRepository = providerRepository,
            accessibilityRepository = accessibilityRepository,
        )

        SessionRuntime.initialize(
            engineFactory = { sessionFactory.create().getOrThrow() },
            historyStore = historyStore,
        )
    }
}
