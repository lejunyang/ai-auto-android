package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：检测设备是否具备内置扫码所需相机硬件，不发现或信任外部扫码应用。
 */

import android.content.Context
import android.content.pm.PackageManager

object AndroidLanQrScanner {
    fun cameraHardware(context: Context): CameraHardware =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            CameraHardware.PRESENT
        } else {
            CameraHardware.ABSENT
        }
}
