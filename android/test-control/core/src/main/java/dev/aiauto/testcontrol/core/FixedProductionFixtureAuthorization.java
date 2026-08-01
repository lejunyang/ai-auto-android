package dev.aiauto.testcontrol.core;

/**
 * 功能用途：只为 N47 三个固定本地场景签发 debug disposable emulator 授权。
 */

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;

public final class FixedProductionFixtureAuthorization {
    public static final String TEST_TOKEN =
        "N47_FIXED_PRODUCTION_FIXTURE_AUTHORIZATION_V1";
    public static final String TEST_SIGNING_DIGEST =
        "a1bb4a7cdfacab4e6cfd050225ae2d6dfd8b8194d172f4f0326eeb585e1aae36";
    private static final Map<String, Set<String>> TARGETS = Map.of(
        "production-native-fixture",
        Set.of("dev.aiauto.android", "dev.aiauto.fixture"),
        "production-webview-fixture",
        Set.of("dev.aiauto.android", "dev.aiauto.webfixture"),
        "production-canvas-fixture",
        Set.of("dev.aiauto.android", "dev.aiauto.webfixture")
    );

    private FixedProductionFixtureAuthorization() {
    }

    public static ProductionFixtureAuthorizationGrant authorize(
        String token,
        TestIdentity identity,
        String scenarioId
    ) {
        if (!constantTimeEquals(TEST_TOKEN, token)) {
            throw failure(TestControlError.TOKEN_INVALID);
        }
        Set<String> packages = TARGETS.get(scenarioId);
        if (packages == null) {
            throw failure(TestControlError.SCOPE_DENIED);
        }
        N31EmulatorGate.validateTrustedIdentity(identity);
        if (!constantTimeEquals(TEST_SIGNING_DIGEST, identity.appSigningDigest())) {
            throw failure(TestControlError.APP_SIGNING_MISMATCH);
        }
        return new ProductionFixtureAuthorizationGrant(scenarioId, packages, identity);
    }

    public static void validateUnchanged(
        ProductionFixtureAuthorizationGrant grant,
        TestIdentity actualIdentity
    ) {
        if (grant == null || actualIdentity == null) {
            throw failure(TestControlError.TOKEN_INVALID);
        }
        N31EmulatorGate.validateUnchanged(grant.identity(), actualIdentity);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual == null
            ? new byte[0]
            : actual.getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.isEqual(left, right);
        } finally {
            java.util.Arrays.fill(left, (byte) 0);
            java.util.Arrays.fill(right, (byte) 0);
        }
    }

    private static TestControlException failure(TestControlError error) {
        return new TestControlException(
            error,
            "Production fixture authorization rejected: " + error.name()
        );
    }
}
