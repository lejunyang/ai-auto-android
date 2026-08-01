package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：定义受信外部二维码 scanner 的纯数据选择和有界结果契约，隔离 Android
 * Activity 细节并避免在状态中保留原始扫描内容。
 */

data class LanQrScannerCandidate(
    val packageName: String,
    val className: String,
    val exported: Boolean,
    val enabled: Boolean,
    val signingIdentity: LanQrScannerSigningIdentity?,
)

data class LanQrScannerSigningIdentity(
    val currentSignerSha256: List<String>,
    val signingCertificateHistorySha256: List<String>,
    val hasMultipleCurrentSigners: Boolean,
)

data class LanQrScanLaunchSpec(
    val action: String,
    val packageName: String,
    val className: String,
    val stringExtras: Map<String, String>,
)

object TrustedLanQrScannerSelector {
    fun select(candidates: List<LanQrScannerCandidate>): LanQrScannerCandidate? {
        val trusted = candidates.filter { candidate ->
            candidate.packageName to candidate.className in TRUSTED_COMPONENTS &&
                candidate.exported &&
                candidate.enabled &&
                candidate.signingIdentity.isTrusted()
        }
        return trusted.singleOrNull()
    }

    private fun LanQrScannerSigningIdentity?.isTrusted(): Boolean =
        this != null &&
            !hasMultipleCurrentSigners &&
            currentSignerSha256 == listOf(FDROID_SIGNER_SHA256) &&
            signingCertificateHistorySha256 == listOf(FDROID_SIGNER_SHA256)

    private val TRUSTED_COMPONENTS = setOf(
        "com.google.zxing.client.android" to
            "com.google.zxing.client.android.CaptureActivity",
    )

    /*
     * 信任来源：F-Droid 官方仓库
     * https://f-droid.org/repo/com.google.zxing.client.android_108.apk
     * 版本 4.7.8(108)，APK SHA-256:
     * 2ed4c2661ed0e2e56b2980d59291dacd58040d219cb7e83b3f6db1102d2ed483。
     * 证书摘要由 Android SDK 36 apksigner verify --print-certs 实测；未知 Play 证书不接受。
     */
    private const val FDROID_SIGNER_SHA256 =
        "1f97ed3c5800111d4627d53512bb38102fda0385c45f763b4b92d6341d29f1ad"
}

fun LanQrScannerCandidate.toLaunchSpec(): LanQrScanLaunchSpec =
    LanQrScanLaunchSpec(
        action = "com.google.zxing.client.android.SCAN",
        packageName = packageName,
        className = className,
        stringExtras = mapOf(
            "SCAN_MODE" to "QR_CODE_MODE",
            "PROMPT_MESSAGE" to "扫描桌面端显示的 AI 安卓自动化 LAN 邀请二维码",
        ),
    )

sealed interface LanQrScanResult {
    data class Success(val payload: String) : LanQrScanResult

    data object Cancelled : LanQrScanResult

    data object PermissionDenied : LanQrScanResult

    data object Invalid : LanQrScanResult

    data object ProviderFailed : LanQrScanResult
}

object LanQrScanResultParser {
    fun parse(
        completed: Boolean,
        values: Map<String, String?>,
    ): LanQrScanResult {
        if (!completed) {
            return if (values[SCAN_ERROR_CODE] == CAMERA_PERMISSION_DENIED) {
                LanQrScanResult.PermissionDenied
            } else {
                LanQrScanResult.Cancelled
            }
        }
        val format = values[SCAN_RESULT_FORMAT]
        val payload = values[SCAN_RESULT]
        if (
            format !in QR_FORMATS ||
            payload.isNullOrBlank() ||
            payload.length > MAX_QR_RESULT_BYTES
        ) {
            return LanQrScanResult.Invalid
        }
        val encoded = payload.encodeToByteArray()
        return try {
            if (encoded.size > MAX_QR_RESULT_BYTES) {
                LanQrScanResult.Invalid
            } else {
                LanQrScanResult.Success(payload)
            }
        } finally {
            encoded.fill(0)
        }
    }

    private val QR_FORMATS = setOf("QR_CODE", "QR_CODE_MODE")
    private const val SCAN_RESULT = "SCAN_RESULT"
    private const val SCAN_RESULT_FORMAT = "SCAN_RESULT_FORMAT"
    private const val SCAN_ERROR_CODE = "SCAN_ERROR_CODE"
    private const val CAMERA_PERMISSION_DENIED = "CAMERA_PERMISSION_DENIED"
    private const val MAX_QR_RESULT_BYTES = 64 * 1024
}
