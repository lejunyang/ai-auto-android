package dev.aiauto.testcontrol.core;

/**
 * 测试用途：验证 N31 profile 与原始及聚合 fingerprint 成对固定，拒绝跨 profile 拼接。
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class N31ProfileAttestationTest {
    @Test
    public void fixedProfileRequiresMatchingPairOfFingerprints() {
        N31ProfileAttestation profile = N31ProfileAttestation.requireExpected(
            "api-30",
            "51ec5c4a7122b6894b752eae99ab48f280736f24ef148590222dc92e2ab72707",
            "google/sdk_gphone_arm64/emulator_arm64:11/RSR1.240422.006/12134477:userdebug/dev-keys"
        );
        profile.verifyLocalObservation(api30Observation());

        assertEquals(30, profile.apiLevel());
        assertEquals(1080, profile.widthPixels());
        assertEquals(2400, profile.heightPixels());
        assertEquals(420, profile.densityDpi());
        assertEquals("91.0.4472.114", profile.webViewVersion());
        assertTrue(profile.allowedHardware().contains("ranchu"));
    }

    @Test
    public void crossProfileFingerprintPairIsRejected() {
        TestControlException rejected = assertThrows(
            TestControlException.class,
            () -> N31ProfileAttestation.requireExpected(
                "api-30",
                "082695b5aabb55e9a57136f949d3dc6a9df634b6ae943ef79dd580b5a1f0f86f",
                "google/sdk_gphone_arm64/emulator_arm64:11/RSR1.240422.006/12134477:userdebug/dev-keys"
            )
        );

        assertEquals(TestControlError.DEVICE_ATTESTATION_MISMATCH, rejected.error());
    }

    @Test
    public void everyLocalAttestationFieldFailsClosedOnDrift() {
        N31ProfileAttestation profile = N31ProfileAttestation.requireExpected(
            "api-30",
            "51ec5c4a7122b6894b752eae99ab48f280736f24ef148590222dc92e2ab72707",
            "google/sdk_gphone_arm64/emulator_arm64:11/RSR1.240422.006/12134477:userdebug/dev-keys"
        );
        LocalDeviceObservation baseline = api30Observation();
        LocalDeviceObservation[] drifted = {
            copy(baseline, "wrong/build", null, null, null, null, null, null, null, null, null, null, null),
            copy(baseline, null, 31, null, null, null, null, null, null, null, null, null, null),
            copy(baseline, null, null, "physical", null, null, null, null, null, null, null, null, null),
            copy(baseline, null, null, null, false, null, null, null, null, null, null, null, null),
            copy(baseline, null, null, null, null, 720, null, null, null, null, null, null, null),
            copy(baseline, null, null, null, null, null, 1280, null, null, null, null, null, null),
            copy(baseline, null, null, null, null, null, null, 320, null, null, null, null, null),
            copy(baseline, null, null, null, null, null, null, null, "en-US", null, null, null, null),
            copy(baseline, null, null, null, null, null, null, null, null, "UTC", null, null, null),
            copy(baseline, null, null, null, null, null, null, null, null, null, "0", null, null),
            copy(baseline, null, null, null, null, null, null, null, null, null, null, "wrong.webview", null),
            copy(baseline, null, null, null, null, null, null, null, null, null, null, null, "0.0.0")
        };

        for (LocalDeviceObservation observation : drifted) {
            assertEquals(
                TestControlError.DEVICE_ATTESTATION_MISMATCH,
                assertThrows(
                    TestControlException.class,
                    () -> profile.verifyLocalObservation(observation)
                ).error()
            );
        }
    }

    @Test
    public void unknownProfileAndBuildFingerprintAreRejected() {
        assertEquals(
            TestControlError.DEVICE_ATTESTATION_MISMATCH,
            assertThrows(
                TestControlException.class,
                () -> N31ProfileAttestation.requireExpected(
                    "api-35",
                    "c".repeat(64),
                    "unknown/fingerprint"
                )
            ).error()
        );
    }

    private static LocalDeviceObservation api30Observation() {
        return new LocalDeviceObservation(
            "google/sdk_gphone_arm64/emulator_arm64:11/RSR1.240422.006/12134477:userdebug/dev-keys",
            30,
            "ranchu",
            true,
            1080,
            2400,
            420,
            "zh-CN",
            "Asia/Shanghai",
            "2",
            "com.google.android.webview",
            "91.0.4472.114"
        );
    }

    private static LocalDeviceObservation copy(
        LocalDeviceObservation baseline,
        String buildFingerprint,
        Integer apiLevel,
        String hardware,
        Boolean qemuLike,
        Integer widthPixels,
        Integer heightPixels,
        Integer densityDpi,
        String localeTag,
        String timezoneId,
        String navigationMode,
        String webViewPackage,
        String webViewVersion
    ) {
        return new LocalDeviceObservation(
            buildFingerprint == null ? baseline.buildFingerprint() : buildFingerprint,
            apiLevel == null ? baseline.apiLevel() : apiLevel,
            hardware == null ? baseline.hardware() : hardware,
            qemuLike == null ? baseline.qemuLike() : qemuLike,
            widthPixels == null ? baseline.widthPixels() : widthPixels,
            heightPixels == null ? baseline.heightPixels() : heightPixels,
            densityDpi == null ? baseline.densityDpi() : densityDpi,
            localeTag == null ? baseline.localeTag() : localeTag,
            timezoneId == null ? baseline.timezoneId() : timezoneId,
            navigationMode == null ? baseline.navigationMode() : navigationMode,
            webViewPackage == null ? baseline.webViewPackage() : webViewPackage,
            webViewVersion == null ? baseline.webViewVersion() : webViewVersion
        );
    }
}
