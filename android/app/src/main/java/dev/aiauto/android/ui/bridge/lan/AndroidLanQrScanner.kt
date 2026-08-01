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
        fun discover(context: Context): AndroidLanQrScanner? {
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
                )
            }
            val selected = TrustedLanQrScannerSelector.select(candidates) ?: return null
            return AndroidLanQrScanner(selected.toLaunchSpec())
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
