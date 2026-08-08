package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：定义内置扫码页面返回给配对状态机的有界结果，不保留相机帧或原始 Intent。
 */

sealed interface LanQrScanResult {
    data class Success(val payload: String) : LanQrScanResult

    data object Cancelled : LanQrScanResult

    data object PermissionDenied : LanQrScanResult

    data object Invalid : LanQrScanResult

    data object ProviderFailed : LanQrScanResult
}
