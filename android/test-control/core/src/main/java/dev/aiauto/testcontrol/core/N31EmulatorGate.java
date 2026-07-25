package dev.aiauto.testcontrol.core;

/**
 * 功能用途：只信任 N31 已验收的三套 AVD 指纹，并集中执行写设置前的身份失败关闭检查。
 */

import java.security.MessageDigest;
import java.util.Set;

public final class N31EmulatorGate {
    private static final Set<String> TRUSTED_FINGERPRINTS = Set.of(
        "51ec5c4a7122b6894b752eae99ab48f280736f24ef148590222dc92e2ab72707",
        "082695b5aabb55e9a57136f949d3dc6a9df634b6ae943ef79dd580b5a1f0f86f",
        "1ac57e687c4b30bdefdb296b1b79158e9e9b3d8297f13817a9edb153ecf5ea8f"
    );

    private N31EmulatorGate() {
    }

    public static void validateTrustedIdentity(TestIdentity identity) {
        if (!identity.isEmulator()) {
            throw failure(TestControlError.EMULATOR_REQUIRED);
        }
        if (!TRUSTED_FINGERPRINTS.contains(identity.avdFingerprint())) {
            throw failure(TestControlError.AVD_FINGERPRINT_MISMATCH);
        }
        if (!constantTimeEquals(TestIdentity.REQUIRED_MARKER, identity.testOnlyMarker())) {
            throw failure(TestControlError.TEST_MARKER_MISMATCH);
        }
        if (!constantTimeEquals("debug", identity.buildVariant())) {
            throw failure(TestControlError.DEBUG_BUILD_REQUIRED);
        }
        if (!constantTimeEquals(identity.appSigningDigest(), identity.callerSigningDigest())) {
            throw failure(TestControlError.CALLER_SIGNING_MISMATCH);
        }
    }

    public static void validateUnchanged(TestIdentity expected, TestIdentity actual) {
        if (!actual.isEmulator()) {
            throw failure(TestControlError.EMULATOR_REQUIRED);
        }
        if (!constantTimeEquals(expected.emulatorSerial(), actual.emulatorSerial())) {
            throw failure(TestControlError.SERIAL_MISMATCH);
        }
        if (!constantTimeEquals(expected.avdFingerprint(), actual.avdFingerprint())) {
            throw failure(TestControlError.AVD_FINGERPRINT_MISMATCH);
        }
        if (!constantTimeEquals(expected.appSigningDigest(), actual.appSigningDigest())) {
            throw failure(TestControlError.APP_SIGNING_MISMATCH);
        }
        if (!constantTimeEquals(expected.callerSigningDigest(), actual.callerSigningDigest())) {
            throw failure(TestControlError.CALLER_SIGNING_MISMATCH);
        }
        validateTrustedIdentity(actual);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
            left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            right.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private static TestControlException failure(TestControlError error) {
        return new TestControlException(error, "Test control request rejected: " + error.name());
    }
}
