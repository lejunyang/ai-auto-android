package dev.aiauto.android.automation.session.coordinator

/**
 * 功能用途：应用级会话运行时单例，持有唯一的 SessionCoordinator 实例。
 *
 * 架构边界：
 * - 本对象是应用级 singleton，不与 Activity 生命周期绑定。
 * - coordinator 在 Service.onCreate 中初始化（Context 安全），Activity 不负责初始化。
 * - ViewModel 通过 [getCoordinator] 获取实例并订阅状态，不创建 engine。
 * - 进程恢复时 coordinator 保持 Idle，不谎称继续；陈旧会话由 coordinator 内部判断。
 *
 * 注意：engineFactory 和 historyStore 由 Android 层注入，确保 coordinator 能创建 engine 并写历史。
 */
object SessionRuntime {
    @Volatile
    private var coordinator: SessionCoordinator? = null

    /**
     * 初始化 coordinator。由 Service.onCreate 调用，确保 Context 安全。
     * 重复调用时忽略（保持单例）。
     *
     * @param engineFactory 创建 engine 的工厂，由 Service 注入。
     * @param historyStore 会话历史持久化，为 null 时跳过历史写入。
     */
    fun initialize(
        engineFactory: () -> dev.aiauto.android.automation.session.AutomationSessionEngine,
        historyStore: dev.aiauto.android.automation.session.history.SessionHistoryStore?,
    ) {
        if (coordinator != null) return
        synchronized(this) {
            if (coordinator == null) {
                coordinator = SessionCoordinator(
                    engineFactory = engineFactory,
                    historyStore = historyStore,
                )
            }
        }
    }

    /** 获取 coordinator 实例；未初始化时返回 null。 */
    fun getCoordinator(): SessionCoordinator? = coordinator

    /** 仅供测试或应用清理时重置。生产环境不应调用。 */
    fun resetForTesting() {
        coordinator?.close()
        coordinator = null
    }
}
