package dev.aiauto.testcontrol.core;

/**
 * 功能用途：承载由 N31 编排器和 Android 签名检查共同提供的不可降级测试身份。
 */
public record TestIdentity(
    String emulatorSerial,
    String avdFingerprint,
    String appSigningDigest,
    String callerSigningDigest,
    String testOnlyMarker,
    String buildVariant
) {
    public static final String REQUIRED_MARKER = "AI_AUTO_TEST_ONLY_V1";

    public TestIdentity {
        emulatorSerial = requireText(emulatorSerial, "emulatorSerial");
        avdFingerprint = requireSha256(avdFingerprint, "avdFingerprint");
        appSigningDigest = requireSha256(appSigningDigest, "appSigningDigest");
        callerSigningDigest = requireSha256(callerSigningDigest, "callerSigningDigest");
        testOnlyMarker = requireText(testOnlyMarker, "testOnlyMarker");
        buildVariant = requireText(buildVariant, "buildVariant");
    }

    public boolean isEmulator() {
        return emulatorSerial.matches("^emulator-[0-9]{4,5}$");
    }

    @Override
    public String toString() {
        return "TestIdentity[serial=" + emulatorSerial
            + ", emulator=" + isEmulator()
            + ", variant=" + buildVariant
            + ", identityDigests=<redacted>]";
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name).toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
