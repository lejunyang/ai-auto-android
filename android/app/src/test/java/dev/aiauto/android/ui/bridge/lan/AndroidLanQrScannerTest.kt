package dev.aiauto.android.ui.bridge.lan

/**
 * 测试用途：验证内置扫码只依赖设备相机硬件，不查询或信任任何外部扫码应用。
 */

import android.content.Context
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidLanQrScannerTest {
    @Test
    fun `camera hardware reports present and absent without component queries`() {
        val packageManager = mockk<PackageManager>()
        val context = mockk<Context>()
        every { context.packageManager } returns packageManager
        every {
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        } returnsMany listOf(true, false)

        assertEquals(CameraHardware.PRESENT, AndroidLanQrScanner.cameraHardware(context))
        assertEquals(CameraHardware.ABSENT, AndroidLanQrScanner.cameraHardware(context))
    }
}
