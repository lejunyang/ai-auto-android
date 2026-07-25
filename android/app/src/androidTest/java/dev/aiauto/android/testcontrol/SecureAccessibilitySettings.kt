package dev.aiauto.android.testcontrol

/**
 * 测试用途：以最小 shell permission identity 精确启用测试服务，并在 finally 恢复原安全设置。
 */

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.ContentResolver
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

import dev.aiauto.android.accessibility.ScreenshotTestAccessibilityService
import dev.aiauto.testcontrol.core.N31EmulatorGate
import dev.aiauto.testcontrol.core.TestIdentity

class SecureAccessibilitySettings(
    instrumentation: Instrumentation,
    context: Context,
    private val identityProvider: () -> TestIdentity,
) {
    private val automation = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    )
    private val resolver: ContentResolver = context.contentResolver
    private val accessibilityManager =
        context.getSystemService(AccessibilityManager::class.java)

    fun <T> withServiceEnabled(
        enableIntent: DebugAccessibilityIntent,
        disableIntent: DebugAccessibilityIntent,
        block: () -> T,
    ): T {
        check(enableIntent.enabled)
        check(!disableIntent.enabled)
        check(enableIntent.componentName == disableIntent.componentName)
        val expected = enableIntent.verifiedIdentity
        N31EmulatorGate.validateUnchanged(expected, disableIntent.verifiedIdentity)
        N31EmulatorGate.validateUnchanged(expected, identityProvider())
        check(isInstalled(enableIntent.componentName)) {
            "The merged debug manifest does not expose the expected accessibility service"
        }

        val originalServices = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        val originalEnabled = Settings.Secure.getString(
            resolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
        )
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        var primaryFailure: Throwable? = null
        try {
            writeAccessibilityEnabled(expected, "0")
            writeEnabledServices(
                expected,
                addService(originalServices, enableIntent.componentName.flattenToString()),
            )
            writeAccessibilityEnabled(expected, "1")
            awaitState(
                expected = expected,
                services = addService(
                    originalServices,
                    enableIntent.componentName.flattenToString(),
                ),
                accessibilityEnabled = "1",
                componentName = enableIntent.componentName,
                componentEnabled = true,
            )
            return block()
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var restoreFailure: Throwable? = null
            try {
                N31EmulatorGate.validateUnchanged(expected, identityProvider())
                runCatching {
                    writeAccessibilityEnabled(expected, "0")
                }.onFailure { error ->
                    restoreFailure = error
                }
                runCatching {
                    writeEnabledServices(expected, originalServices)
                }.onFailure { error ->
                    restoreFailure?.addSuppressed(error) ?: run {
                        restoreFailure = error
                    }
                }
                runCatching {
                    writeAccessibilityEnabled(expected, originalEnabled)
                }.onFailure { error ->
                    restoreFailure?.addSuppressed(error) ?: run {
                        restoreFailure = error
                    }
                }
                runCatching {
                    awaitState(
                        expected = expected,
                        services = originalServices,
                        accessibilityEnabled = originalEnabled,
                        componentName = enableIntent.componentName,
                        componentEnabled = originalEnabled == "1" &&
                            containsService(originalServices, enableIntent.componentName),
                    )
                }.onFailure { error ->
                    restoreFailure?.addSuppressed(error) ?: run {
                        restoreFailure = error
                    }
                }
            } catch (error: Throwable) {
                restoreFailure?.addSuppressed(error) ?: run {
                    restoreFailure = error
                }
            } finally {
                automation.dropShellPermissionIdentity()
            }
            restoreFailure?.let { error ->
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(error)
                } else {
                    throw error
                }
            }
        }
    }

    private fun writeEnabledServices(expected: TestIdentity, value: String?) {
        N31EmulatorGate.validateUnchanged(expected, identityProvider())
        check(
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                value,
            ),
        ) {
            "Unable to update enabled accessibility services"
        }
        check(
            Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) == value,
        ) {
            "Enabled accessibility services did not match the requested value"
        }
    }

    private fun writeAccessibilityEnabled(expected: TestIdentity, value: String?) {
        N31EmulatorGate.validateUnchanged(expected, identityProvider())
        check(
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                value,
            ),
        ) {
            "Unable to update accessibility enabled state"
        }
        check(
            Settings.Secure.getString(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
            ) == value,
        ) {
            "Accessibility enabled state did not match the requested value"
        }
    }

    private fun awaitState(
        expected: TestIdentity,
        services: String?,
        accessibilityEnabled: String?,
        componentName: ComponentName,
        componentEnabled: Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + STATE_TIMEOUT_MS
        do {
            N31EmulatorGate.validateUnchanged(expected, identityProvider())
            val settingsMatch =
                Settings.Secure.getString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ) == services &&
                    Settings.Secure.getString(
                        resolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                    ) == accessibilityEnabled
            val managerMatch = isEnabled(componentName) == componentEnabled
            val connectionMatch =
                (ScreenshotTestAccessibilityService.connectedService != null) ==
                componentEnabled
            if (settingsMatch && managerMatch && connectionMatch) {
                return
            }
            SystemClock.sleep(STATE_POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Accessibility settings did not reach the verified target state")
    }

    private fun isInstalled(componentName: ComponentName): Boolean =
        accessibilityManager.installedAccessibilityServiceList.any { service ->
            service.resolveInfo?.serviceInfo?.let { info ->
                ComponentName(info.packageName, info.name) == componentName
            } == true
        }

    private fun isEnabled(componentName: ComponentName): Boolean =
        accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).any { service ->
            service.resolveInfo?.serviceInfo?.let { info ->
                ComponentName(info.packageName, info.name) == componentName
            } == true
        }

    private fun containsService(existing: String?, componentName: ComponentName): Boolean =
        existing.orEmpty()
            .split(':')
            .filter(String::isNotBlank)
            .mapNotNull { value -> ComponentName.unflattenFromString(value) }
            .contains(componentName)

    private fun addService(existing: String?, service: String): String =
        existing.orEmpty()
            .split(':')
            .filter(String::isNotBlank)
            .plus(service)
            .distinct()
            .joinToString(":")

    private companion object {
        const val STATE_TIMEOUT_MS = 15_000L
        const val STATE_POLL_INTERVAL_MS = 100L
    }
}
