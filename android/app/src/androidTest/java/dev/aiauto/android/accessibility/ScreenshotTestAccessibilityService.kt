package dev.aiauto.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.snapshot.AccessibilitySnapshotter
import dev.aiauto.android.accessibility.snapshot.recycleSafely

class ScreenshotTestAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = this
        connectedLatch.countDown()
    }

    override fun onDestroy() {
        if (connectedService === this) {
            connectedService = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun snapshot(expectedPackage: String): AccessibilityResult<UiNodeSnapshot> {
        val root = rootInActiveWindow ?: return AccessibilityResult.Failure(
            code = AccessibilityErrorCode.WINDOW_UNAVAILABLE,
            message = "The test activity has no active accessibility window",
            retryable = true,
        )
        return try {
            if (root.packageName?.toString() != expectedPackage) {
                AccessibilityResult.Failure(
                    code = AccessibilityErrorCode.PACKAGE_NOT_ALLOWED,
                    message = "The active test window does not match the requested package",
                    retryable = true,
                )
            } else {
                AccessibilitySnapshotter().snapshot(root)
            }
        } finally {
            root.recycleSafely()
        }
    }

    companion object {
        @Volatile
        var connectedService: ScreenshotTestAccessibilityService? = null
            private set

        private var connectedLatch = CountDownLatch(1)

        fun awaitConnected(timeoutSeconds: Long): ScreenshotTestAccessibilityService {
            check(connectedLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                "Test accessibility service did not connect"
            }
            return checkNotNull(connectedService)
        }

        fun reset() {
            connectedService = null
            connectedLatch = CountDownLatch(1)
        }
    }
}
