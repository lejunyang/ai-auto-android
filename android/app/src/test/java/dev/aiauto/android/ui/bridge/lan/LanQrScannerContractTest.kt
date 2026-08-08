package dev.aiauto.android.ui.bridge.lan

/**
 * 测试用途：验证内置扫码结果类型不包含相机帧、外部 Intent 或第三方组件身份。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LanQrScannerContractTest {
    @Test
    fun `success retains only bounded payload value`() {
        val payload = """{"kind":"ai-auto-lan-invitation"}"""
        val result = LanQrScanResult.Success(payload)

        assertEquals(payload, result.payload)
        assertFalse(result.toString().contains("camera"))
        assertFalse(result.toString().contains("intent"))
    }

    @Test
    fun `cancel permission invalid and provider failures remain explicit`() {
        assertEquals(LanQrScanResult.Cancelled, LanQrScanResult.Cancelled)
        assertEquals(LanQrScanResult.PermissionDenied, LanQrScanResult.PermissionDenied)
        assertEquals(LanQrScanResult.Invalid, LanQrScanResult.Invalid)
        assertEquals(LanQrScanResult.ProviderFailed, LanQrScanResult.ProviderFailed)
    }
}
