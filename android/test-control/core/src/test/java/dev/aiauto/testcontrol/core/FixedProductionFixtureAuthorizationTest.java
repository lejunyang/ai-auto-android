package dev.aiauto.testcontrol.core;

/**
 * 测试用途：验证 N47 固定授权只接受 N31 debug 双签名身份、固定 token 与三个 fixture 场景。
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.util.Set;
import org.junit.Test;

public final class FixedProductionFixtureAuthorizationTest {
    private static final String SERIAL = "emulator-5554";
    private static final String FINGERPRINT =
        "082695b5aabb55e9a57136f949d3dc6a9df634b6ae943ef79dd580b5a1f0f86f";
    private static final String SIGNING =
        FixedProductionFixtureAuthorization.TEST_SIGNING_DIGEST;

    @Test
    public void fixedTokenIssuesScenarioBoundGrantWithoutLeakingToken() {
        ProductionFixtureAuthorizationGrant grant =
            FixedProductionFixtureAuthorization.authorize(
                FixedProductionFixtureAuthorization.TEST_TOKEN,
                identity(SERIAL, FINGERPRINT, SIGNING, SIGNING, "debug"),
                "production-native-fixture"
            );

        assertEquals("production-native-fixture", grant.scenarioId());
        assertEquals(
            Set.of("dev.aiauto.android", "dev.aiauto.fixture"),
            grant.targetPackages()
        );
        assertFalse(grant.toString().contains(FixedProductionFixtureAuthorization.TEST_TOKEN));
    }

    @Test
    public void tokenScenarioPhysicalReleaseAndSignerMismatchFailClosed() {
        assertRejected(
            "wrong-token",
            identity(SERIAL, FINGERPRINT, SIGNING, SIGNING, "debug"),
            "production-native-fixture",
            TestControlError.TOKEN_INVALID
        );
        assertRejected(
            FixedProductionFixtureAuthorization.TEST_TOKEN,
            identity(SERIAL, FINGERPRINT, SIGNING, SIGNING, "debug"),
            "production-unknown-fixture",
            TestControlError.SCOPE_DENIED
        );
        assertRejected(
            FixedProductionFixtureAuthorization.TEST_TOKEN,
            identity("R58M123ABC", FINGERPRINT, SIGNING, SIGNING, "debug"),
            "production-native-fixture",
            TestControlError.EMULATOR_REQUIRED
        );
        assertRejected(
            FixedProductionFixtureAuthorization.TEST_TOKEN,
            identity(SERIAL, FINGERPRINT, SIGNING, SIGNING, "release"),
            "production-native-fixture",
            TestControlError.DEBUG_BUILD_REQUIRED
        );
        assertRejected(
            FixedProductionFixtureAuthorization.TEST_TOKEN,
            identity(SERIAL, FINGERPRINT, SIGNING, "b".repeat(64), "debug"),
            "production-native-fixture",
            TestControlError.CALLER_SIGNING_MISMATCH
        );
        assertRejected(
            FixedProductionFixtureAuthorization.TEST_TOKEN,
            identity(SERIAL, FINGERPRINT, "c".repeat(64), "c".repeat(64), "debug"),
            "production-native-fixture",
            TestControlError.APP_SIGNING_MISMATCH
        );
    }

    @Test
    public void grantRejectsIdentityDriftBeforeEachSideEffect() {
        ProductionFixtureAuthorizationGrant grant =
            FixedProductionFixtureAuthorization.authorize(
                FixedProductionFixtureAuthorization.TEST_TOKEN,
                identity(SERIAL, FINGERPRINT, SIGNING, SIGNING, "debug"),
                "production-webview-fixture"
            );

        TestControlException rejected = assertThrows(
            TestControlException.class,
            () -> FixedProductionFixtureAuthorization.validateUnchanged(
                grant,
                identity("emulator-5556", FINGERPRINT, SIGNING, SIGNING, "debug")
            )
        );
        assertEquals(TestControlError.SERIAL_MISMATCH, rejected.error());
    }

    private static void assertRejected(
        String token,
        TestIdentity identity,
        String scenarioId,
        TestControlError expected
    ) {
        TestControlException rejected = assertThrows(
            TestControlException.class,
            () -> FixedProductionFixtureAuthorization.authorize(token, identity, scenarioId)
        );
        assertEquals(expected, rejected.error());
        assertFalse(rejected.getMessage().contains(token));
    }

    private static TestIdentity identity(
        String serial,
        String fingerprint,
        String appSigning,
        String callerSigning,
        String variant
    ) {
        return new TestIdentity(
            serial,
            fingerprint,
            appSigning,
            callerSigning,
            TestIdentity.REQUIRED_MARKER,
            variant
        );
    }
}
