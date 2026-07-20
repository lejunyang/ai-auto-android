package dev.aiauto.android.automation.recording

/**
 * 功能用途：实现 ScriptEnvironmentProvider 对应的语义录制、脚本持久化或确定性回放能力。
 */

import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowManager

import dev.aiauto.android.BuildConfig

fun interface ScriptEnvironmentProvider {
    fun capture(): ScriptEnvironment
}

class AndroidScriptEnvironmentProvider(
    private val context: Context,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
    private val appVersion: String = BuildConfig.VERSION_NAME,
) : ScriptEnvironmentProvider {
    override fun capture(): ScriptEnvironment {
        val configuration = context.resources.configuration
        val windowManager = requireNotNull(
            context.getSystemService(WindowManager::class.java),
        ) {
            "WindowManager is unavailable"
        }
        val bounds = windowManager.maximumWindowMetrics.bounds
        return ScriptEnvironment(
            apiLevel = apiLevel,
            logicalWidth = bounds.width(),
            logicalHeight = bounds.height(),
            densityDpi = configuration.densityDpi,
            rotation = displayRotation(windowManager),
            locale = configuration.locales[0].toLanguageTag(),
            fontScale = configuration.fontScale,
            appVersion = appVersion,
        )
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(windowManager: WindowManager): Int? =
        when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> null
        }
}
