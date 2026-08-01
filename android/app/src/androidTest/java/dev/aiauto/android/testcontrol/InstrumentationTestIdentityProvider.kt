package dev.aiauto.android.testcontrol

/**
 * 测试用途：从显式 N31 参数和实际 APK 证书重建测试身份，避免信任调用方伪造的签名摘要。
 */

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
        val local = if (arguments.containsKey(ARGUMENT_N45_RESOLUTION)) {
            awaitN45MatrixGeometry()
        } else {
            observeLocalDevice()
        }
        profile.verifyLocalObservation(
            if (arguments.containsKey(ARGUMENT_N45_RESOLUTION)) {
                LocalDeviceObservation(
                    local.buildFingerprint(),
                    local.apiLevel(),
                    local.hardware(),
                    local.qemuLike(),
                    N31_BASE_WIDTH,
                    N31_BASE_HEIGHT,
                    N31_BASE_DENSITY_DPI,
                    local.localeTag(),
                    local.timezoneId(),
                    local.navigationMode(),
                    local.webViewPackage(),
                    local.webViewVersion(),
                )
            } else {
                local
            },
        )
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

    private fun awaitN45MatrixGeometry(): LocalDeviceObservation {
        val resolution = requiredArgument(ARGUMENT_N45_RESOLUTION)
        val rotation = requiredArgument(ARGUMENT_N45_ROTATION).toIntOrNull()
        check(resolution in N45_RESOLUTIONS && rotation in N45_ROTATIONS) {
            "N45 matrix geometry is outside the fixed test contract"
        }
        val parts = resolution.split('x').map(String::toInt)
        val expectedWidth = if (rotation == 90) parts[1] else parts[0]
        val expectedHeight = if (rotation == 90) parts[0] else parts[1]
        val deadline = SystemClock.uptimeMillis() + N45_GEOMETRY_TIMEOUT_MS
        var observation: LocalDeviceObservation
        do {
            observation = observeLocalDevice()
            if (
                observation.widthPixels() == expectedWidth &&
                observation.heightPixels() == expectedHeight &&
                observation.densityDpi() == N31_BASE_DENSITY_DPI
            ) {
                return observation
            }
            SystemClock.sleep(N45_GEOMETRY_POLL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        error(
            "N45 matrix geometry did not settle: " +
                "expected=${expectedWidth}x$expectedHeight@$N31_BASE_DENSITY_DPI, " +
                "actual=${observation.widthPixels()}x${observation.heightPixels()}@" +
                observation.densityDpi(),
        )
    }

    companion object {
        const val ARGUMENT_SERIAL = "n32EmulatorSerial"
        const val ARGUMENT_PROFILE_ID = "n32ProfileId"
        const val ARGUMENT_FINGERPRINT = "n32AvdFingerprint"
        const val ARGUMENT_BUILD_FINGERPRINT = "n32ExpectedBuildFingerprint"
        const val ARGUMENT_MARKER = "n32TestOnlyMarker"
        const val ARGUMENT_N45_RESOLUTION = "n45MatrixResolution"
        const val ARGUMENT_N45_ROTATION = "n45MatrixRotation"
        private const val N31_BASE_WIDTH = 1_080
        private const val N31_BASE_HEIGHT = 2_400
        private const val N31_BASE_DENSITY_DPI = 420
        private const val N45_GEOMETRY_TIMEOUT_MS = 10_000L
        private const val N45_GEOMETRY_POLL_MS = 100L
        private val N45_RESOLUTIONS = setOf("720x1600", "1080x2400", "1440x3200")
        private val N45_ROTATIONS = setOf(0, 90)
    }
}
