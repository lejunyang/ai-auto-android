package dev.aiauto.android.testcontrol

/**
 * 测试用途：从显式 N31 参数和实际 APK 证书重建测试身份，避免信任调用方伪造的签名摘要。
 */

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.webkit.WebView

import dev.aiauto.android.BuildConfig
import dev.aiauto.testcontrol.core.LocalDeviceObservation
import dev.aiauto.testcontrol.core.N31ProfileAttestation
import dev.aiauto.testcontrol.core.TestIdentity
import java.security.MessageDigest
import java.util.TimeZone

class InstrumentationTestIdentityProvider(
    private val targetContext: Context,
    private val instrumentationContext: Context,
    private val arguments: Bundle,
) {
    fun current(): TestIdentity {
        val targetPackage = targetContext.packageName
        val callerPackage = instrumentationContext.packageName
        val profile = N31ProfileAttestation.requireExpected(
            requiredArgument(ARGUMENT_PROFILE_ID),
            requiredArgument(ARGUMENT_FINGERPRINT),
            requiredArgument(ARGUMENT_BUILD_FINGERPRINT),
        )
        profile.verifyLocalObservation(observeLocalDevice())
        /*
         * ADB serial 无法由普通 App 可靠读取；它由 N31 runner/ANDROID_SERIAL 外层边界
         * 显式提供，但只有本地 profile attestation 通过后才会进入 TestIdentity。
         */
        return TestIdentity(
            requiredArgument(ARGUMENT_SERIAL),
            profile.aggregateFingerprint(),
            signingDigest(targetContext, targetPackage),
            signingDigest(instrumentationContext, callerPackage),
            requiredArgument(ARGUMENT_MARKER),
            BuildConfig.BUILD_TYPE,
        )
    }

    private fun observeLocalDevice(): LocalDeviceObservation {
        val windowManager = targetContext.getSystemService(WindowManager::class.java)
        val bounds = windowManager.maximumWindowMetrics.bounds
        val configuration = targetContext.resources.configuration
        val webView = checkNotNull(WebView.getCurrentWebViewPackage()) {
            "Current WebView package is unavailable"
        }
        val hardware = Build.HARDWARE.orEmpty()
        val qemuLike = hardware == "ranchu" ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk_gphone", ignoreCase = true)
        return LocalDeviceObservation(
            Build.FINGERPRINT.orEmpty(),
            Build.VERSION.SDK_INT,
            hardware,
            qemuLike,
            bounds.width(),
            bounds.height(),
            configuration.densityDpi,
            configuration.locales[0].toLanguageTag(),
            TimeZone.getDefault().id,
            Settings.Secure.getString(
                targetContext.contentResolver,
                "navigation_mode",
            ).orEmpty(),
            webView.packageName.orEmpty(),
            webView.versionName.orEmpty(),
        )
    }

    private fun signingDigest(context: Context, packageName: String): String {
        val packageInfo = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signers = checkNotNull(packageInfo.signingInfo).apkContentsSigners
        check(signers.size == 1) {
            "N32 requires exactly one current APK signer"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(signers.single().toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun requiredArgument(name: String): String =
        checkNotNull(arguments.getString(name)?.takeIf(String::isNotBlank)) {
            "Missing required N32 instrumentation argument: $name"
        }

    companion object {
        const val ARGUMENT_SERIAL = "n32EmulatorSerial"
        const val ARGUMENT_PROFILE_ID = "n32ProfileId"
        const val ARGUMENT_FINGERPRINT = "n32AvdFingerprint"
        const val ARGUMENT_BUILD_FINGERPRINT = "n32ExpectedBuildFingerprint"
        const val ARGUMENT_MARKER = "n32TestOnlyMarker"
    }
}
