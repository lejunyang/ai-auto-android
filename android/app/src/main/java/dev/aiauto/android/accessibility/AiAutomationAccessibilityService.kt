package dev.aiauto.android.accessibility

// 功能用途：实现 AiAutomationAccessibilityService 对应的无障碍观察、截图、动作或用户触摸安全控制。

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Build
import android.view.accessibility.AccessibilityEvent

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import dev.aiauto.android.accessibility.action.AccessibilityActionRouter
import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.AccessibilityScreenshot
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.selector.SelectorMatcher
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.accessibility.snapshot.AccessibilitySnapshotter
import dev.aiauto.android.accessibility.snapshot.recycleSafely
import dev.aiauto.android.automation.recording.RecordingRuntime
import dev.aiauto.android.automation.session.AutomationSessionRuntime

class AiAutomationAccessibilityService :
    AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var settingsRepository: AccessibilitySettingsRepository
    private lateinit var backend: AndroidAccessibilityBackend
    private lateinit var actionRouter: AccessibilityActionRouter
    private lateinit var screenshotController: AccessibilityScreenshotController
    private lateinit var screenshotExecutor: ExecutorService
    private lateinit var userTouchMonitor: UserTouchMonitor

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = AccessibilitySettingsRepository.from(this)
        userTouchMonitor = UserTouchMonitor(
            notifyUserTouch = AutomationSessionRuntime::notifyUserTouch,
            currentSessionId = AutomationSessionRuntime::activeSessionId,
        )
        backend = AndroidAccessibilityBackend(
            service = this,
            settingsRepository = settingsRepository,
            snapshotter = AccessibilitySnapshotter(),
            userTouchMonitor = userTouchMonitor,
        )
        actionRouter = AccessibilityActionRouter(
            backend = backend,
            selectorMatcher = SelectorMatcher(),
        )
        screenshotExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "accessibility-screenshot").apply {
                isDaemon = true
            }
        }
        screenshotController = AccessibilityScreenshotController(
            platform = AndroidScreenshotPlatform(
                service = this,
                callbackExecutor = screenshotExecutor,
            ),
            snapshot = ::snapshot,
            acquireAuthorization = AutomationSessionRuntime::acquireScreenshotAuthorization,
            isAuthorizationActive = AutomationSessionRuntime::isScreenshotAuthorizationActive,
        )
        settingsRepository.registerListener(this)
        applyTargetPackages()
        AccessibilityRuntime.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }
        userTouchMonitor.onAccessibilityEvent(event)
        if (!RecordingRuntime.isListening()) {
            return
        }
        val source = event.source ?: run {
            RecordingRuntime.publish(event, source = null)
            return
        }
        try {
            val snapshot = AccessibilitySnapshotter().snapshot(source)
            RecordingRuntime.publish(
                event = event,
                source = (snapshot as? AccessibilityResult.Success)?.value,
            )
        } finally {
            source.recycleSafely()
        }
    }

    override fun onInterrupt() = Unit

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?,
    ) {
        applyTargetPackages()
    }

    override fun onDestroy() {
        AccessibilityRuntime.detach(this)
        if (::settingsRepository.isInitialized) {
            settingsRepository.unregisterListener(this)
        }
        if (::screenshotExecutor.isInitialized) {
            screenshotExecutor.shutdownNow()
        }
        if (::userTouchMonitor.isInitialized) {
            userTouchMonitor.clearAutomationActions()
        }
        super.onDestroy()
    }

    internal fun snapshot(
        expectedPackage: String? = null,
    ): AccessibilityResult<UiNodeSnapshot> {
        when (val validation = backend.validateTarget(expectedPackage)) {
            is AccessibilityResult.Failure -> return validation
            is AccessibilityResult.Success -> Unit
        }
        return when (val opened = backend.openNodeSession(expectedPackage)) {
            is AccessibilityResult.Failure -> opened
            is AccessibilityResult.Success -> opened.value.use { session ->
                AccessibilityResult.Success(session.rootSnapshot)
            }
        }
    }

    internal fun execute(
        command: AccessibilityCommand,
    ): AccessibilityResult<ActionExecution> = actionRouter.execute(command)

    internal suspend fun captureScreenshot(
        expectedPackage: String,
    ): AccessibilityResult<AccessibilityScreenshot> =
        screenshotController.capture(expectedPackage)

    private fun applyTargetPackages() {
        val settings = settingsRepository.load()
        val packages = if (settings.isReady) {
            settings.targetPackages.toTypedArray()
        } else {
            // A null or empty package list means every package to AccessibilityServiceInfo.
            arrayOf(packageName)
        }
        val currentServiceInfo = serviceInfo
        val touchConfiguration = userTouchMonitor.configuration(
            eventTypes = currentServiceInfo.eventTypes,
            flags = currentServiceInfo.flags,
            motionEventSources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                currentServiceInfo.motionEventSources
            } else {
                0
            },
        )
        serviceInfo = currentServiceInfo.apply {
            packageNames = packages
            eventTypes = touchConfiguration.eventTypes
            flags = touchConfiguration.flags
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                motionEventSources = touchConfiguration.motionEventSources
            }
        }
    }

    private fun activePackageName(): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycleSafely()
        }
    }
}

object AccessibilityRuntime {
    @Volatile
    private var service: AiAutomationAccessibilityService? = null

    fun isAvailable(): Boolean = service != null

    fun snapshot(expectedPackage: String? = null): AccessibilityResult<UiNodeSnapshot> =
        service?.snapshot(expectedPackage) ?: serviceDisabled()

    fun execute(command: AccessibilityCommand): AccessibilityResult<ActionExecution> =
        service?.execute(command) ?: serviceDisabled()

    suspend fun captureScreenshot(
        expectedPackage: String,
    ): AccessibilityResult<AccessibilityScreenshot> =
        service?.captureScreenshot(expectedPackage) ?: serviceDisabled()

    internal fun attach(accessibilityService: AiAutomationAccessibilityService) {
        service = accessibilityService
    }

    internal fun detach(accessibilityService: AiAutomationAccessibilityService) {
        if (service === accessibilityService) {
            service = null
        }
    }

    private fun serviceDisabled(): AccessibilityResult.Failure =
        AccessibilityResult.Failure(
            code = AccessibilityErrorCode.SERVICE_DISABLED,
            message = "The accessibility service is not enabled",
            retryable = true,
        )
}
