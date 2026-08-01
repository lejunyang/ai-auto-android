package dev.aiauto.android.ui.bridge.lan

/**
 * 测试用途：验证外部二维码 provider 只选择受信显式组件，并严格分类扫描结果。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanQrScannerContractTest {
    @Test
    fun `selector accepts only one enabled exported trusted scanner`() {
        val selected = TrustedLanQrScannerSelector.select(
            listOf(
                LanQrScannerCandidate(
                    packageName = "untrusted.scanner",
                    className = "untrusted.scanner.ScanActivity",
                    exported = true,
                    enabled = true,
                ),
                LanQrScannerCandidate(
                    packageName = "com.google.zxing.client.android",
                    className = "com.google.zxing.client.android.CaptureActivity",
                    exported = true,
                    enabled = true,
                ),
            ),
        )

        assertEquals("com.google.zxing.client.android", selected?.packageName)
        assertEquals(
            "com.google.zxing.client.android.CaptureActivity",
            selected?.className,
        )
    }

    @Test
    fun `selector rejects disabled hidden or ambiguous trusted handlers`() {
        assertNull(
            TrustedLanQrScannerSelector.select(
                listOf(
                    LanQrScannerCandidate(
                        packageName = "com.google.zxing.client.android",
                        className = "com.google.zxing.client.android.CaptureActivity",
                        exported = true,
                        enabled = false,
                    ),
                ),
            ),
        )
        assertNull(
            TrustedLanQrScannerSelector.select(
                listOf(
                    LanQrScannerCandidate(
                        packageName = "com.google.zxing.client.android",
                        className = "com.google.zxing.client.android.CaptureActivity",
                        exported = false,
                        enabled = true,
                    ),
                ),
            ),
        )
        assertNull(
            TrustedLanQrScannerSelector.select(
                listOf(
                    LanQrScannerCandidate(
                        packageName = "com.google.zxing.client.android",
                        className = "com.google.zxing.client.android.CaptureActivity",
                        exported = true,
                        enabled = true,
                    ),
                    LanQrScannerCandidate(
                        packageName = "com.google.zxing.client.android",
                        className = "com.google.zxing.client.android.CaptureActivity",
                        exported = true,
                        enabled = true,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `result parser accepts only bounded QR payload`() {
        val result = LanQrScanResultParser.parse(
            completed = true,
            values = mapOf(
                "SCAN_RESULT" to """{"kind":"ai-auto-lan-invitation"}""",
                "SCAN_RESULT_FORMAT" to "QR_CODE",
            ),
        )

        assertTrue(result is LanQrScanResult.Success)
        assertEquals(
            """{"kind":"ai-auto-lan-invitation"}""",
            (result as LanQrScanResult.Success).payload,
        )
        assertEquals(
            LanQrScanResult.Invalid,
            LanQrScanResultParser.parse(
                completed = true,
                values = mapOf(
                    "SCAN_RESULT" to "not-a-qr",
                    "SCAN_RESULT_FORMAT" to "CODE_128",
                ),
            ),
        )
        assertEquals(
            LanQrScanResult.Invalid,
            LanQrScanResultParser.parse(
                completed = true,
                values = mapOf(
                    "SCAN_RESULT" to "x".repeat(64 * 1024 + 1),
                    "SCAN_RESULT_FORMAT" to "QR_CODE",
                ),
            ),
        )
        assertEquals(
            LanQrScanResult.Cancelled,
            LanQrScanResultParser.parse(completed = false, values = emptyMap()),
        )
    }
}
