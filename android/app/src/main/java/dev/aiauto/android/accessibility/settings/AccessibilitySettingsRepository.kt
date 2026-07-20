package dev.aiauto.android.accessibility.settings

// 功能用途：实现 AccessibilitySettingsRepository 对应的无障碍服务状态与授权目标包配置。

import android.content.Context
import android.content.SharedPreferences

data class AccessibilitySettings(
    val disclosureAccepted: Boolean,
    val targetPackages: Set<String>,
) {
    val isReady: Boolean
        get() = disclosureAccepted && targetPackages.isNotEmpty()
}

sealed interface TargetPackageParseResult {
    data class Valid(val packages: Set<String>) : TargetPackageParseResult

    data class Invalid(val invalidValues: List<String>) : TargetPackageParseResult
}

object TargetPackageParser {
    fun parse(rawValue: String): TargetPackageParseResult {
        val values = rawValue
            .split(PACKAGE_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val invalid = values.filterNot(::isValidPackageName)
        if (invalid.isNotEmpty() || values.size > MAX_TARGET_PACKAGES) {
            return TargetPackageParseResult.Invalid(
                invalidValues = if (values.size > MAX_TARGET_PACKAGES) {
                    invalid + "maximum-$MAX_TARGET_PACKAGES-packages"
                } else {
                    invalid
                },
            )
        }
        return TargetPackageParseResult.Valid(values.toSortedSet())
    }

    private fun isValidPackageName(value: String): Boolean =
        value.length in 3..255 && PACKAGE_NAME.matches(value)

    private val PACKAGE_SEPARATOR = Regex("[,\\s]+")
    private val PACKAGE_NAME =
        Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private const val MAX_TARGET_PACKAGES = 32
}

class AccessibilitySettingsRepository(
    private val preferences: SharedPreferences,
) {
    fun load(): AccessibilitySettings = AccessibilitySettings(
        disclosureAccepted = preferences.getBoolean(KEY_DISCLOSURE_ACCEPTED, false),
        targetPackages = preferences.getStringSet(KEY_TARGET_PACKAGES, emptySet())
            ?.toSortedSet()
            .orEmpty(),
    )

    fun save(settings: AccessibilitySettings) {
        check(
            preferences.edit()
                .putBoolean(KEY_DISCLOSURE_ACCEPTED, settings.disclosureAccepted)
                .putStringSet(KEY_TARGET_PACKAGES, settings.targetPackages)
                .commit(),
        ) { "Failed to persist accessibility settings" }
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFERENCES_NAME = "accessibility-settings"
        private const val KEY_DISCLOSURE_ACCEPTED = "disclosure-accepted"
        private const val KEY_TARGET_PACKAGES = "target-packages"

        fun from(context: Context): AccessibilitySettingsRepository =
            AccessibilitySettingsRepository(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
    }
}
