package dev.aiauto.android.ui.bridge.lan

/**
 * 测试用途：验证 Android scanner adapter 只发现受信显式 Activity，并收缩返回结果。
 */

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.activity.result.ActivityResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLanQrScannerTest {
    @Test
    fun `discovery returns explicit trusted component and fixed qr mode`() {
        val packageManager = mockk<PackageManager>()
        val context = mockk<Context>()
        every { context.packageManager } returns packageManager
        every {
            packageManager.queryIntentActivities(any(), PackageManager.MATCH_DEFAULT_ONLY)
        } returns listOf(
            resolveInfo(
                packageName = "com.google.zxing.client.android",
                className = "com.google.zxing.client.android.CaptureActivity",
            ),
        )

        val scanner = requireNotNull(AndroidLanQrScanner.discover(context))
        val spec = scanner.launchSpec

        assertEquals("com.google.zxing.client.android.SCAN", spec.action)
        assertEquals("com.google.zxing.client.android", spec.packageName)
        assertEquals(
            "com.google.zxing.client.android.CaptureActivity",
            spec.className,
        )
        assertEquals("QR_CODE_MODE", spec.stringExtras["SCAN_MODE"])
        assertFalse(spec.stringExtras.containsKey("SCAN_RESULT"))
    }

    @Test
    fun `discovery rejects untrusted component and reports camera hardware`() {
        val packageManager = mockk<PackageManager>()
        val context = mockk<Context>()
        every { context.packageManager } returns packageManager
        every {
            packageManager.queryIntentActivities(any(), PackageManager.MATCH_DEFAULT_ONLY)
        } returns listOf(
            resolveInfo(
                packageName = "untrusted.scanner",
                className = "untrusted.scanner.CaptureActivity",
            ),
        )
        every {
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        } returnsMany listOf(false, true)

        assertNull(AndroidLanQrScanner.discover(context))
        assertEquals(CameraHardware.ABSENT, AndroidLanQrScanner.cameraHardware(context))
        assertEquals(CameraHardware.PRESENT, AndroidLanQrScanner.cameraHardware(context))
    }

    @Test
    fun `activity result maps qr success and cancellation without retaining extras`() {
        val packageManager = mockk<PackageManager>()
        val context = mockk<Context>()
        every { context.packageManager } returns packageManager
        every {
            packageManager.queryIntentActivities(any(), PackageManager.MATCH_DEFAULT_ONLY)
        } returns listOf(
            resolveInfo(
                packageName = "com.google.zxing.client.android",
                className = "com.google.zxing.client.android.CaptureActivity",
            ),
        )
        val scanner = requireNotNull(AndroidLanQrScanner.discover(context))
        val payload = """{"kind":"ai-auto-lan-invitation"}"""
        val resultIntent = mockk<Intent>()
        every { resultIntent.getStringExtra("SCAN_RESULT") } returns payload
        every { resultIntent.getStringExtra("SCAN_RESULT_FORMAT") } returns "QR_CODE"
        every { resultIntent.getStringExtra("SCAN_ERROR_CODE") } returns null
        every { resultIntent.removeExtra(any()) } returns Unit
        val success = scanner.parseResult(
            ActivityResult(
                Activity.RESULT_OK,
                resultIntent,
            ),
        )

        assertTrue(success is LanQrScanResult.Success)
        assertEquals(payload, (success as LanQrScanResult.Success).payload)
        assertEquals(
            LanQrScanResult.Cancelled,
            scanner.parseResult(ActivityResult(Activity.RESULT_CANCELED, null)),
        )
        verify(exactly = 1) { resultIntent.removeExtra("SCAN_RESULT") }
        verify(exactly = 1) { resultIntent.removeExtra("SCAN_RESULT_FORMAT") }
        verify(exactly = 1) { resultIntent.removeExtra("SCAN_ERROR_CODE") }
    }

    private fun resolveInfo(
        packageName: String,
        className: String,
    ): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = className
            exported = true
            enabled = true
        }
    }
}
