package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：发现受信外部二维码 Activity、创建显式扫描 Intent，并把返回值收缩到纯契约。
 */

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResult
import java.security.MessageDigest

class AndroidLanQrScanner private constructor(
    val launchSpec: LanQrScanLaunchSpec,
) {
    fun createIntent(): Intent {
        val intent = Intent(launchSpec.action)
        intent.component = ComponentName(launchSpec.packageName, launchSpec.className)
        launchSpec.stringExtras.forEach { (name, value) ->
            intent.putExtra(name, value)
        }
        return intent
    }

    fun parseResult(result: ActivityResult): LanQrScanResult {
        val data = result.data
        return try {
            LanQrScanResultParser.parse(
                completed = result.resultCode == Activity.RESULT_OK,
                values = mapOf(
                    SCAN_RESULT to data?.getStringExtra(SCAN_RESULT),
                    SCAN_RESULT_FORMAT to data?.getStringExtra(SCAN_RESULT_FORMAT),
                    SCAN_ERROR_CODE to data?.getStringExtra(SCAN_ERROR_CODE),
                ),
            )
        } finally {
            data?.removeExtra(SCAN_RESULT)
            data?.removeExtra(SCAN_RESULT_FORMAT)
            data?.removeExtra(SCAN_ERROR_CODE)
        }
    }

    companion object {
        fun discover(context: Context): AndroidLanQrScanner? = try {
            val packageManager = context.packageManager
            val candidates = packageManager.queryIntentActivities(
                Intent(SCAN_ACTION).addCategory(Intent.CATEGORY_DEFAULT),
                PackageManager.MATCH_DEFAULT_ONLY,
            ).mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                LanQrScannerCandidate(
                    packageName = activityInfo.packageName,
                    className = activityInfo.name,
                    exported = activityInfo.exported,
                    enabled = activityInfo.enabled,
                    signingIdentity = packageManager.signingIdentity(activityInfo.packageName),
                )
            }
            val selected = TrustedLanQrScannerSelector.select(candidates) ?: return null
            AndroidLanQrScanner(selected.toLaunchSpec())
        } catch (_: Exception) {
            null
        }

        fun cameraHardware(context: Context): CameraHardware =
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                CameraHardware.PRESENT
            } else {
                CameraHardware.ABSENT
            }

        private const val SCAN_ACTION = "com.google.zxing.client.android.SCAN"
        private const val SCAN_RESULT = "SCAN_RESULT"
        private const val SCAN_RESULT_FORMAT = "SCAN_RESULT_FORMAT"
        private const val SCAN_ERROR_CODE = "SCAN_ERROR_CODE"
    }
}

private fun PackageManager.signingIdentity(
    packageName: String,
): LanQrScannerSigningIdentity? = try {
    val info = getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        .signingInfo ?: return null
    val current = info.apkContentsSigners
        ?.map { signature -> signature.sha256() }
        ?: return null
    val history = info.signingCertificateHistory
        ?.map { signature -> signature.sha256() }
        ?: return null
    LanQrScannerSigningIdentity(
        currentSignerSha256 = current,
        signingCertificateHistorySha256 = history,
        hasMultipleCurrentSigners = info.hasMultipleSigners(),
    )
} catch (_: Exception) {
    null
}

private fun android.content.pm.Signature.sha256(): String {
    val certificate = toByteArray()
    return try {
        MessageDigest.getInstance("SHA-256")
            .digest(certificate)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    } finally {
        certificate.fill(0)
    }
}
