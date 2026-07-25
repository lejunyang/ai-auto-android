package dev.aiauto.testcontrol.core;

/**
 * 功能用途：承载 Android 进程独立采集的本地设备事实，不混入 host 提供的 ADB serial。
 */
public record LocalDeviceObservation(
    String buildFingerprint,
    int apiLevel,
    String hardware,
    boolean qemuLike,
    int widthPixels,
    int heightPixels,
    int densityDpi,
    String localeTag,
    String timezoneId,
    String navigationMode,
    String webViewPackage,
    String webViewVersion
) {
}
