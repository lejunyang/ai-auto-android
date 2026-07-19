package dev.aiauto.android.automation.session

internal fun interface UserTouchStopHandler {
    fun stopForUserTouch()
}

internal class ActiveAutomationSessionRegistry {
    private val lock = Any()
    private var activeSession: ActiveSession? = null

    fun register(
        targetPackage: String,
        screenshotsAllowed: Boolean,
        stopHandler: UserTouchStopHandler,
    ): AutoCloseable {
        val session = ActiveSession(
            targetPackage = targetPackage,
            screenshotsAllowed = screenshotsAllowed,
            stopHandler = stopHandler,
        )
        synchronized(lock) {
            check(activeSession == null) { "Another automation session is already active." }
            activeSession = session
        }
        return AutoCloseable {
            synchronized(lock) {
                if (activeSession === session) {
                    activeSession = null
                }
            }
        }
    }

    fun isScreenshotAuthorized(targetPackage: String): Boolean = synchronized(lock) {
        activeSession?.let { session ->
            session.targetPackage == targetPackage && session.screenshotsAllowed
        } == true
    }

    fun notifyUserTouch(targetPackage: String): Boolean {
        val stopHandler = synchronized(lock) {
            activeSession
                ?.takeIf { it.targetPackage == targetPackage }
                ?.stopHandler
        } ?: return false
        stopHandler.stopForUserTouch()
        return true
    }

    private data class ActiveSession(
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

    fun isScreenshotAuthorized(targetPackage: String): Boolean =
        registry.isScreenshotAuthorized(targetPackage)

    fun notifyUserTouch(targetPackage: String): Boolean =
        registry.notifyUserTouch(targetPackage)
}
