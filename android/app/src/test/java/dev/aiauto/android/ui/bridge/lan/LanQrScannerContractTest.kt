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
                    signingIdentity = trustedSigningIdentity(),
                ),
                LanQrScannerCandidate(
                    packageName = "com.google.zxing.client.android",
                    className = "com.google.zxing.client.android.CaptureActivity",
                    exported = true,
                    enabled = true,
                    signingIdentity = trustedSigningIdentity(),
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
                        signingIdentity = trustedSigningIdentity(),
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
                        signingIdentity = trustedSigningIdentity(),
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
                        signingIdentity = trustedSigningIdentity(),
                    ),
                    LanQrScannerCandidate(
                        packageName = "com.google.zxing.client.android",
                        className = "com.google.zxing.client.android.CaptureActivity",
                        exported = true,
                        enabled = true,
                        signingIdentity = trustedSigningIdentity(),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `selector rejects wrong absent multiple and historical only signers`() {
        val component = LanQrScannerCandidate(
            packageName = "com.google.zxing.client.android",
            className = "com.google.zxing.client.android.CaptureActivity",
            exported = true,
            enabled = true,
            signingIdentity = null,
        )
        val cases = listOf(
            null,
            LanQrScannerSigningIdentity(
                currentSignerSha256 = listOf(WRONG_SIGNER),
                signingCertificateHistorySha256 = listOf(WRONG_SIGNER),
                hasMultipleCurrentSigners = false,
            ),
            LanQrScannerSigningIdentity(
                currentSignerSha256 = listOf(TRUSTED_SIGNER, WRONG_SIGNER),
                signingCertificateHistorySha256 = listOf(TRUSTED_SIGNER, WRONG_SIGNER),
                hasMultipleCurrentSigners = true,
            ),
            LanQrScannerSigningIdentity(
                currentSignerSha256 = listOf(WRONG_SIGNER),
                signingCertificateHistorySha256 = listOf(TRUSTED_SIGNER, WRONG_SIGNER),
                hasMultipleCurrentSigners = false,
            ),
            LanQrScannerSigningIdentity(
                currentSignerSha256 = listOf(TRUSTED_SIGNER),
                signingCertificateHistorySha256 = listOf(WRONG_SIGNER, TRUSTED_SIGNER),
                hasMultipleCurrentSigners = false,
            ),
            LanQrScannerSigningIdentity(
                currentSignerSha256 = listOf(TRUSTED_SIGNER),
                signingCertificateHistorySha256 = emptyList(),
                hasMultipleCurrentSigners = false,
            ),
        )

        cases.forEach { identity ->
            assertNull(
                TrustedLanQrScannerSelector.select(
                    listOf(component.copy(signingIdentity = identity)),
                ),
            )
        }
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

    private companion object {
        const val TRUSTED_SIGNER =
            "1f97ed3c5800111d4627d53512bb38102fda0385c45f763b4b92d6341d29f1ad"
        const val WRONG_SIGNER =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        fun trustedSigningIdentity() = LanQrScannerSigningIdentity(
            currentSignerSha256 = listOf(TRUSTED_SIGNER),
            signingCertificateHistorySha256 = listOf(TRUSTED_SIGNER),
            hasMultipleCurrentSigners = false,
        )
    }
}
