package dev.aiauto.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent

import dev.aiauto.android.accessibility.action.AccessibilityActionRouter
import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.selector.SelectorMatcher
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.accessibility.snapshot.AccessibilitySnapshotter
import dev.aiauto.android.accessibility.snapshot.recycleSafely
import dev.aiauto.android.automation.recording.RecordingRuntime

class AiAutomationAccessibilityService :
    AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var settingsRepository: AccessibilitySettingsRepository
    private lateinit var backend: AndroidAccessibilityBackend
    private lateinit var actionRouter: AccessibilityActionRouter

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = AccessibilitySettingsRepository.from(this)
        backend = AndroidAccessibilityBackend(
            service = this,
            settingsRepository = settingsRepository,
            snapshotter = AccessibilitySnapshotter(),
        )
        actionRouter = AccessibilityActionRouter(
            backend = backend,
            selectorMatcher = SelectorMatcher(),
        )
        settingsRepository.registerListener(this)
        applyTargetPackages()
        AccessibilityRuntime.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !RecordingRuntime.isListening()) {
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

    private fun applyTargetPackages() {
        val settings = settingsRepository.load()
        val packages = if (settings.isReady) {
            settings.targetPackages.toTypedArray()
        } else {
            // A null or empty package list means every package to AccessibilityServiceInfo.
            arrayOf(packageName)
        }
        serviceInfo = serviceInfo.apply {
            packageNames = packages
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
