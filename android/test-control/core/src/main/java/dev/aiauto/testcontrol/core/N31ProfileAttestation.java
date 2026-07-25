package dev.aiauto.testcontrol.core;

/**
 * 功能用途：固定 N31 三套设备 profile 的本地证明字段，防止 host 参数自行声明可信设备。
 */

import java.util.Map;
import java.util.Set;

public record N31ProfileAttestation(
    String profileId,
    String aggregateFingerprint,
    String buildFingerprint,
    int apiLevel,
    int widthPixels,
    int heightPixels,
    int densityDpi,
    String localeTag,
    String timezoneId,
    String navigationMode,
    String webViewPackage,
    String webViewVersion,
    Set<String> allowedHardware
) {
    private static final Map<String, N31ProfileAttestation> PROFILES = Map.of(
        "api-30",
        profile(
            "api-30",
            "51ec5c4a7122b6894b752eae99ab48f280736f24ef148590222dc92e2ab72707",
            "google/sdk_gphone_arm64/emulator_arm64:11/RSR1.240422.006/12134477:userdebug/dev-keys",
            30,
            "91.0.4472.114"
        ),
        "api-33",
        profile(
            "api-33",
            "082695b5aabb55e9a57136f949d3dc6a9df634b6ae943ef79dd580b5a1f0f86f",
            "google/sdk_gphone64_arm64/emu64a:13/TE1A.240213.009/12342917:userdebug/dev-keys",
            33,
            "109.0.5414.123"
        ),
        "api-34",
        profile(
            "api-34",
            "1ac57e687c4b30bdefdb296b1b79158e9e9b3d8297f13817a9edb153ecf5ea8f",
            "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.050/12077443:userdebug/dev-keys",
            34,
            "113.0.5672.136"
        )
    );

    public N31ProfileAttestation {
        allowedHardware = Set.copyOf(allowedHardware);
    }

    public static N31ProfileAttestation requireExpected(
        String profileId,
        String aggregateFingerprint,
        String buildFingerprint
    ) {
        N31ProfileAttestation profile = PROFILES.get(profileId);
        if (profile == null
            || !profile.aggregateFingerprint().equals(aggregateFingerprint)
            || !profile.buildFingerprint().equals(buildFingerprint)) {
            throw new TestControlException(
                TestControlError.DEVICE_ATTESTATION_MISMATCH,
                "Test control request rejected: DEVICE_ATTESTATION_MISMATCH"
            );
        }
        return profile;
    }

    public void verifyLocalObservation(LocalDeviceObservation observation) {
        if (observation == null
            || !buildFingerprint.equals(observation.buildFingerprint())
            || apiLevel != observation.apiLevel()
            || widthPixels != observation.widthPixels()
            || heightPixels != observation.heightPixels()
            || densityDpi != observation.densityDpi()
            || !localeTag.equals(observation.localeTag())
            || !timezoneId.equals(observation.timezoneId())
            || !navigationMode.equals(observation.navigationMode())
            || !webViewPackage.equals(observation.webViewPackage())
            || !webViewVersion.equals(observation.webViewVersion())
            || !allowedHardware.contains(observation.hardware())
            || !observation.qemuLike()) {
            throw new TestControlException(
                TestControlError.DEVICE_ATTESTATION_MISMATCH,
                "Test control request rejected: DEVICE_ATTESTATION_MISMATCH"
            );
        }
    }

    private static N31ProfileAttestation profile(
        String id,
        String aggregateFingerprint,
        String buildFingerprint,
        int apiLevel,
        String webViewVersion
    ) {
        return new N31ProfileAttestation(
            id,
            aggregateFingerprint,
            buildFingerprint,
            apiLevel,
            1080,
            2400,
            420,
            "zh-CN",
            "Asia/Shanghai",
            "2",
            "com.google.android.webview",
            webViewVersion,
            Set.of("ranchu")
        );
    }
}
