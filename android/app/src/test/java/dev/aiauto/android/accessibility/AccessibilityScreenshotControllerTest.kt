package dev.aiauto.android.accessibility

// 测试用途：验证 AccessibilityScreenshotController 的功能契约、失败语义及自动化安全边界。

import android.accessibilityservice.AccessibilityService
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer

import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.AccessibilityScreenshot
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityScreenshotControllerTest {
    @Test
    fun `capture rejects unsupported platform before authorization BitsUT`() = runTest {
        var platformCalls = 0
        val controller = controller(
            sdkInt = 29,
            platform = ScreenshotPlatform { _, _ -> platformCalls += 1 },
        )

        val result = controller.capture(TARGET_PACKAGE)

        assertFailure(result, AccessibilityErrorCode.SCREENSHOT_NOT_SUPPORTED)
        assertEquals(0, platformCalls)
    }

    @Test
    fun `capture rejects session without screenshot authorization BitsUT`() = runTest {
        var snapshotCalls = 0
        val controller = AccessibilityScreenshotController(
            platform = ScreenshotPlatform { _, _ -> error("platform must not be called") },
            snapshot = {
                snapshotCalls += 1
                AccessibilityResult.Success(node())
            },
            isAuthorized = { false },
            sdkInt = 30,
        )

        val result = controller.capture(TARGET_PACKAGE)

        assertFailure(result, AccessibilityErrorCode.SCREENSHOT_NOT_AUTHORIZED)
        assertEquals(0, snapshotCalls)
    }

    @Test
    fun `capture rejects redacted sensitive descendant before platform call BitsUT`() = runTest {
        var platformCalls = 0
        val controller = controller(
            snapshot = AccessibilityResult.Success(
                node(children = listOf(node(sensitive = true))),
            ),
            platform = ScreenshotPlatform { _, _ -> platformCalls += 1 },
        )

        val result = controller.capture(TARGET_PACKAGE)

        assertFailure(result, AccessibilityErrorCode.SCREENSHOT_SENSITIVE_CONTENT)
        assertEquals(0, platformCalls)
    }

    @Test
    fun `capture clears screenshot when authorization is revoked during capture BitsUT`() = runTest {
        var authorized = true
        val bytes = byteArrayOf(1, 2, 3)
        val controller = AccessibilityScreenshotController(
            platform = ScreenshotPlatform { _, callback ->
                authorized = false
                callback(
                    PlatformScreenshotResult.Success(
                        AccessibilityScreenshot(bytes, 2, 3, 4),
                    ),
                )
            },
            snapshot = { AccessibilityResult.Success(node()) },
            isAuthorized = { authorized },
            sdkInt = 30,
        )

        val result = controller.capture(TARGET_PACKAGE)

        assertFailure(result, AccessibilityErrorCode.SCREENSHOT_NOT_AUTHORIZED)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `capture clears screenshot when sensitive content appears during capture BitsUT`() = runTest {
        var snapshotCalls = 0
        val bytes = byteArrayOf(1, 2, 3)
        val controller = AccessibilityScreenshotController(
            platform = ScreenshotPlatform { _, callback ->
                callback(
                    PlatformScreenshotResult.Success(
                        AccessibilityScreenshot(bytes, 2, 3, 4),
                    ),
                )
            },
            snapshot = {
                snapshotCalls += 1
                AccessibilityResult.Success(node(sensitive = snapshotCalls > 1))
            },
            isAuthorized = { true },
            sdkInt = 30,
        )

        val result = controller.capture(TARGET_PACKAGE)

        assertFailure(result, AccessibilityErrorCode.SCREENSHOT_SENSITIVE_CONTENT)
        assertEquals(2, snapshotCalls)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `capture rejects and clears screenshot above local byte budget BitsUT`() = runTest {
        val bytes = ByteArray(1_048_577) { 1 }
        val controller = controller(
            platform = ScreenshotPlatform { _, callback ->
                callback(
                    PlatformScreenshotResult.Success(
                        AccessibilityScreenshot(bytes, 2_000, 1_000, 4),
                    ),
                )
            },
        )

        val result = controller.capture(TARGET_PACKAGE)

        assertFailure(result, AccessibilityErrorCode.SCREENSHOT_FAILED)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `capture maps secure window platform failure BitsUT`() = runTest {
        val controller = controller(
            platform = ScreenshotPlatform { _, callback ->
                callback(
                    PlatformScreenshotResult.Failure(
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW,
                    ),
                )
            },
        )

        val result = controller.capture(TARGET_PACKAGE)

        assertFailure(result, AccessibilityErrorCode.SCREENSHOT_SECURE_WINDOW)
    }

    @Test
    fun `successful screenshot can clear owned png bytes BitsUT`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val screenshot = AccessibilityScreenshot(bytes, 2, 3, 4)
        val controller = controller(
            platform = ScreenshotPlatform { _, callback ->
                callback(PlatformScreenshotResult.Success(screenshot))
            },
        )

        val result = controller.capture(TARGET_PACKAGE)

        assertTrue(result is AccessibilityResult.Success)
        (result as AccessibilityResult.Success).value.close()
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `android platform closes hardware buffer when encoder throws BitsUT`() {
        val service = mockk<AccessibilityService>()
        val callbackSlot = slot<AccessibilityService.TakeScreenshotCallback>()
        every {
            service.takeScreenshot(any(), any(), capture(callbackSlot))
        } just runs
        val buffer = mockk<HardwareBuffer>()
        every { buffer.close() } just runs
        val result = mockk<AccessibilityService.ScreenshotResult>()
        every { result.hardwareBuffer } returns buffer
        every { result.colorSpace } returns mockk<ColorSpace>()
        every { result.timestamp } returns 123L
        var platformResult: PlatformScreenshotResult? = null
        val platform = AndroidScreenshotPlatform(
            service = service,
            callbackExecutor = Executor(Runnable::run),
            encoder = HardwareBufferScreenshotEncoder { _, _, _, _, _ ->
                throw IllegalStateException("encoding failed")
            },
        )

        platform.capture(node().bounds) { platformResult = it }
        callbackSlot.captured.onSuccess(result)

        verify(exactly = 1) { buffer.close() }
        assertTrue(platformResult is PlatformScreenshotResult.Failure)
    }

    private fun controller(
        sdkInt: Int = 30,
        snapshot: AccessibilityResult<UiNodeSnapshot> = AccessibilityResult.Success(node()),
        platform: ScreenshotPlatform,
    ) = AccessibilityScreenshotController(
        platform = platform,
        snapshot = { snapshot },
        isAuthorized = { it == TARGET_PACKAGE },
        sdkInt = sdkInt,
    )

    private fun assertFailure(
        result: AccessibilityResult<*>,
        expectedCode: AccessibilityErrorCode,
    ) {
        assertTrue(result is AccessibilityResult.Failure)
        assertEquals(expectedCode, (result as AccessibilityResult.Failure).error.code)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"

        fun node(
            sensitive: Boolean = false,
            children: List<UiNodeSnapshot> = emptyList(),
        ) = UiNodeSnapshot(
            packageName = TARGET_PACKAGE,
            className = "android.view.View",
            resourceId = null,
            text = null,
            contentDescription = null,
            bounds = UiBounds(0, 0, 10, 10),
            actions = emptySet(),
            state = UiNodeState(sensitive = sensitive),
            children = children,
        )
    }
}
