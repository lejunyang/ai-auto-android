package dev.aiauto.android.automation.session

/**
 * 功能用途：实现 AutomationSessionRuntime 对应的受控 AI 自动化会话、风险判断与生命周期管理。
 */

internal fun interface UserTouchStopHandler {
    fun stopForUserTouch()
}

internal data class ScreenshotAuthorization(
    val sessionId: Long,
    val targetPackage: String,
)

internal class ActiveAutomationSessionRegistry {
    private val lock = Any()
    private var activeSession: ActiveSession? = null
    private var nextSessionId = 1L

    fun register(
        targetPackage: String,
        screenshotsAllowed: Boolean,
        stopHandler: UserTouchStopHandler,
    ): AutoCloseable = synchronized(lock) {
        check(activeSession == null) { "Another automation session is already active." }
        // 单调递增的会话代次会绑定截图授权，旧回调不能借新会话继续通过校验。
        val session = ActiveSession(
            sessionId = nextSessionId++,
            targetPackage = targetPackage,
            screenshotsAllowed = screenshotsAllowed,
            stopHandler = stopHandler,
        ).also {
            activeSession = it
        }
        AutoCloseable {
            synchronized(lock) {
                if (activeSession === session) {
                    activeSession = null
                }
            }
        }
    }

    fun acquireScreenshotAuthorization(targetPackage: String): ScreenshotAuthorization? =
        synchronized(lock) {
            activeSession
                ?.takeIf { session ->
                    session.targetPackage == targetPackage && session.screenshotsAllowed
                }
                ?.let { session ->
                    ScreenshotAuthorization(
                        sessionId = session.sessionId,
                        targetPackage = session.targetPackage,
                    )
                }
        }

    fun isScreenshotAuthorizationActive(
        authorization: ScreenshotAuthorization,
    ): Boolean = synchronized(lock) {
        activeSession?.let { session ->
            session.sessionId == authorization.sessionId &&
                session.targetPackage == authorization.targetPackage &&
                session.screenshotsAllowed
        } == true
    }

    fun isScreenshotAuthorized(targetPackage: String): Boolean =
        acquireScreenshotAuthorization(targetPackage) != null

    fun activeSessionId(targetPackage: String): Long? = synchronized(lock) {
        activeSession
            ?.takeIf { it.targetPackage == targetPackage }
            ?.sessionId
    }

    fun notifyUserTouch(targetPackage: String): Boolean {
        // 锁内只提取停止句柄，实际停止在锁外同步执行，避免状态机回调造成锁重入。
        val stopHandler = synchronized(lock) {
            activeSession
                ?.takeIf { it.targetPackage == targetPackage }
                ?.stopHandler
        } ?: return false
        stopHandler.stopForUserTouch()
        return true
    }

    private data class ActiveSession(
        val sessionId: Long,
        val targetPackage: String,
        val screenshotsAllowed: Boolean,
        val stopHandler: UserTouchStopHandler,
    )
}

object AutomationSessionRuntime {
    private val registry = ActiveAutomationSessionRegistry()

    internal fun register(
        targetPackage: String,
        screenshotsAllowed: Boolean,
        stopHandler: UserTouchStopHandler,
    ): AutoCloseable = registry.register(
        targetPackage = targetPackage,
        screenshotsAllowed = screenshotsAllowed,
        stopHandler = stopHandler,
    )

    internal fun acquireScreenshotAuthorization(
        targetPackage: String,
    ): ScreenshotAuthorization? =
        registry.acquireScreenshotAuthorization(targetPackage)

    internal fun isScreenshotAuthorizationActive(
        authorization: ScreenshotAuthorization,
    ): Boolean = registry.isScreenshotAuthorizationActive(authorization)

    fun isScreenshotAuthorized(targetPackage: String): Boolean =
        registry.isScreenshotAuthorized(targetPackage)

    internal fun activeSessionId(targetPackage: String): Long? =
        registry.activeSessionId(targetPackage)

    fun notifyUserTouch(targetPackage: String): Boolean =
        registry.notifyUserTouch(targetPackage)
}
