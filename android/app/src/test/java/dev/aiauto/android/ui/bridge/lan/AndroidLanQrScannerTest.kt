package dev.aiauto.android.ui.bridge.lan

/**
 * 测试用途：验证 Android scanner adapter 只发现受信显式 Activity，并收缩返回结果。
 */

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.activity.result.ActivityResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Base64
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
        signingInfo(
            packageManager = packageManager,
            currentSigners = listOf(TRUSTED_CERTIFICATE),
            historySigners = listOf(TRUSTED_CERTIFICATE),
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
        signingInfo(
            packageManager = packageManager,
            currentSigners = listOf(TRUSTED_CERTIFICATE),
            historySigners = listOf(TRUSTED_CERTIFICATE),
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

    @Test
    fun `discovery rejects same package with wrong or missing signature`() {
        val wrong = packageManagerForTrustedComponent()
        signingInfo(
            packageManager = wrong,
            currentSigners = listOf(WRONG_CERTIFICATE),
            historySigners = listOf(WRONG_CERTIFICATE),
        )
        assertNull(AndroidLanQrScanner.discover(context(wrong)))

        val missing = packageManagerForTrustedComponent()
        every {
            missing.getPackageInfo(
                "com.google.zxing.client.android",
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } returns PackageInfo()
        assertNull(AndroidLanQrScanner.discover(context(missing)))
    }

    @Test
    fun `discovery rejects multiple current and historical only trusted signer`() {
        val multiple = packageManagerForTrustedComponent()
        signingInfo(
            packageManager = multiple,
            currentSigners = listOf(TRUSTED_CERTIFICATE, WRONG_CERTIFICATE),
            historySigners = listOf(TRUSTED_CERTIFICATE, WRONG_CERTIFICATE),
            hasMultipleSigners = true,
        )
        assertNull(AndroidLanQrScanner.discover(context(multiple)))

        val historicalOnly = packageManagerForTrustedComponent()
        signingInfo(
            packageManager = historicalOnly,
            currentSigners = listOf(WRONG_CERTIFICATE),
            historySigners = listOf(TRUSTED_CERTIFICATE, WRONG_CERTIFICATE),
        )
        assertNull(AndroidLanQrScanner.discover(context(historicalOnly)))

        val rotatedHistory = packageManagerForTrustedComponent()
        signingInfo(
            packageManager = rotatedHistory,
            currentSigners = listOf(TRUSTED_CERTIFICATE),
            historySigners = listOf(WRONG_CERTIFICATE, TRUSTED_CERTIFICATE),
        )
        assertNull(AndroidLanQrScanner.discover(context(rotatedHistory)))
    }

    @Test
    fun `discovery fails closed when signing api throws`() {
        val packageManager = packageManagerForTrustedComponent()
        every {
            packageManager.getPackageInfo(
                "com.google.zxing.client.android",
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } throws SecurityException("signing identity unavailable")

        assertNull(AndroidLanQrScanner.discover(context(packageManager)))
    }

    @Test
    fun `discovery fails closed when certificate bytes cannot be read`() {
        val packageManager = packageManagerForTrustedComponent()
        val signingInfo = mockk<SigningInfo>()
        val brokenSignature = mockk<Signature>()
        every { signingInfo.hasMultipleSigners() } returns false
        every { signingInfo.apkContentsSigners } returns arrayOf(brokenSignature)
        every { signingInfo.signingCertificateHistory } returns arrayOf(brokenSignature)
        every { brokenSignature.toByteArray() } throws SecurityException(
            "certificate bytes unavailable",
        )
        every {
            packageManager.getPackageInfo(
                "com.google.zxing.client.android",
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } returns PackageInfo().apply {
            this.signingInfo = signingInfo
        }

        assertNull(AndroidLanQrScanner.discover(context(packageManager)))
    }

    @Test
    fun `discovery fails closed when component query throws`() {
        val packageManager = mockk<PackageManager>()
        every {
            packageManager.queryIntentActivities(any(), PackageManager.MATCH_DEFAULT_ONLY)
        } throws SecurityException("component query unavailable")

        assertNull(AndroidLanQrScanner.discover(context(packageManager)))
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

    private fun packageManagerForTrustedComponent(): PackageManager =
        mockk<PackageManager>().also { packageManager ->
            every {
                packageManager.queryIntentActivities(any(), PackageManager.MATCH_DEFAULT_ONLY)
            } returns listOf(
                resolveInfo(
                    packageName = "com.google.zxing.client.android",
                    className = "com.google.zxing.client.android.CaptureActivity",
                ),
            )
        }

    private fun context(packageManager: PackageManager): Context =
        mockk<Context>().also { context ->
            every { context.packageManager } returns packageManager
        }

    private fun signingInfo(
        packageManager: PackageManager,
        currentSigners: List<ByteArray>,
        historySigners: List<ByteArray>,
        hasMultipleSigners: Boolean = false,
    ) {
        val signingInfo = mockk<SigningInfo>()
        every { signingInfo.hasMultipleSigners() } returns hasMultipleSigners
        every { signingInfo.apkContentsSigners } returns currentSigners.map(::signature).toTypedArray()
        every {
            signingInfo.signingCertificateHistory
        } returns historySigners.map(::signature).toTypedArray()
        every {
            packageManager.getPackageInfo(
                "com.google.zxing.client.android",
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } returns PackageInfo().apply {
            this.signingInfo = signingInfo
        }
    }

    private fun signature(certificate: ByteArray): Signature =
        mockk<Signature>().also { signature ->
            every { signature.toByteArray() } returns certificate.copyOf()
        }

    private companion object {
        val TRUSTED_CERTIFICATE: ByteArray = Base64.getDecoder().decode(
            "MIIDPDCCAiSgAwIBAgIEUGww0TANBgkqhkiG9w0BAQUFADBgMQswCQYDVQQGEwJVSzEM" +
                "MAoGA1UECBMDT1JHMQwwCgYDVQQHEwNPUkcxEzARBgNVBAoTCmZkcm9pZC5vcmcxDzAN" +
                "BgNVBAsTBkZEcm9pZDEPMA0GA1UEAxMGRkRyb2lkMB4XDTEyMTAwMzEyMzQyNVoXDTQw" +
                "MDIxOTEyMzQyNVowYDELMAkGA1UEBhMCVUsxDDAKBgNVBAgTA09SRzEMMAoGA1UEBxMD" +
                "T1JHMRMwEQYDVQQKEwpmZHJvaWQub3JnMQ8wDQYDVQQLEwZGRHJvaWQxDzANBgNVBAMT" +
                "BkZEcm9pZDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAIGgSAATXofQGscG" +
                "09O8PxTDtm2Kn/kg2wtdWh14tqUWnq67Y29JGlCoYs5qDbh4AaKmCMwnueldELA67kEG" +
                "4MUM2gMBJHE9KSWYpcUVuz2Hr411luBjyY6kGTo6QW6iVxUkh3dwMtIIE5IlwwKjeBBE" +
                "DOXJ1MfHVg0q/Oh/uRTvyEbVrn8UgP52cTHEpFAC5z85ZPvzh1Q/+dqetKbRRABkJQ4+" +
                "VyVFoxxCBC5o/abTFgrX4VGz1wHecPii3uMuwWgHyn+W82kffVOdRkr3ErZFVR7+ap6n" +
                "RuZJPNoNpDvnQiX5NbB7JqNWMN3GUB4pLMJqqc4wkVt0W52Z4XLl0eMCAwEAATANBgkq" +
                "hkiG9w0BAQUFAAOCAQEAeVfH4XK0gSqzOicPh9Q78Avn2iFaDZKDrNy720Li4H9BFzyx" +
                "kA09OfoZNLXZRcrTJbJXb0RPtvv2KuIQvwG31z/Phyr8li4vD2IGnAY11WrbaGhTlU6W" +
                "scpMu+eHCM77CPlvw9AYwWBvLvmAeOzDDFT6i54tXtFqCtw8hrMSwRXYi7qJOS3T9sxv" +
                "FT4XeD3EBRNDZiQSYqPBU5a6X5flDV8CbPZjaQe26QFn9Q+MTwJwFGX8Yaa1wWg7w/Y1" +
                "KBWgjbOqUTJ5s5FjgkDO5pbFd6A1akP2Nb4A9q5yrx03zJ6Ol8umuwb68mPha/svbafU" +
                "WjvOY4yr82MnMvy3+E7pyg==",
        )
        val WRONG_CERTIFICATE = "wrong-certificate".encodeToByteArray()
    }
}
