package dev.aiauto.testcontrol.core;

/**
 * 测试用途：验证一次性测试令牌对 scope、模拟器身份、双签名和 debug 边界的完整绑定。
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import org.junit.Before;
import org.junit.Test;

public final class TestTokenAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final String SERIAL = "emulator-5554";
    private static final String FINGERPRINT = "51ec5c4a7122b6894b752eae99ab48f280736f24ef148590222dc92e2ab72707";
    private static final String APP_SIGNING = "a".repeat(64);
    private static final String CALLER_SIGNING = APP_SIGNING;
    private static final String MARKER = "AI_AUTO_TEST_ONLY_V1";
    private static final Duration TTL = Duration.ofMinutes(2);

    private MutableClock clock;
    private TestTokenAuthority authority;

    @Before
    public void setUp() {
        clock = new MutableClock(NOW);
        authority = new TestTokenAuthority(clock, new DeterministicSecureRandom());
    }

    @Test
    public void issuedTokenAuthorizesExactlyOneMatchingScope() {
        IssuedTestToken issued = authority.issue(
            EnumSet.of(TestScope.STATUS_READ, TestScope.SELF_CHECK_READ),
            trustedIdentity(),
            TTL
        );

        AuthorizationGrant grant = authority.authorize(
            issued.value(),
            TestScope.STATUS_READ,
            trustedIdentity()
        );

        assertEquals(TestScope.STATUS_READ, grant.scope());
        assertEquals(SERIAL, grant.identity().emulatorSerial());
        assertFalse(grant.toString().contains(issued.value()));
        TestControlException replay = assertThrows(
            TestControlException.class,
            () -> authority.authorize(
                issued.value(),
                TestScope.SELF_CHECK_READ,
                trustedIdentity()
            )
        );
        assertEquals(TestControlError.TOKEN_REPLAYED, replay.error());
    }

    @Test
    public void expiredTokenIsRejectedAndConsumed() {
        IssuedTestToken issued = authority.issue(
            EnumSet.of(TestScope.STATUS_READ),
            trustedIdentity(),
            TTL
        );
        clock.advance(TTL);

        TestControlException expired = assertThrows(
            TestControlException.class,
            () -> authority.authorize(
                issued.value(),
                TestScope.STATUS_READ,
                trustedIdentity()
            )
        );
        assertEquals(TestControlError.TOKEN_EXPIRED, expired.error());
        assertEquals(
            TestControlError.TOKEN_REPLAYED,
            assertThrows(
                TestControlException.class,
                () -> authority.authorize(
                    issued.value(),
                    TestScope.STATUS_READ,
                    trustedIdentity()
                )
            ).error()
        );
    }

    @Test
    public void scopeEscalationIsRejectedBeforeAnyGrant() {
        IssuedTestToken issued = authority.issue(
            EnumSet.of(TestScope.STATUS_READ),
            trustedIdentity(),
            TTL
        );

        TestControlException rejected = assertThrows(
            TestControlException.class,
            () -> authority.authorize(
                issued.value(),
                TestScope.ACCESSIBILITY_CONTROL,
                trustedIdentity()
            )
        );

        assertEquals(TestControlError.SCOPE_DENIED, rejected.error());
        assertEquals(
            TestControlError.TOKEN_REPLAYED,
            replayError(issued, trustedIdentity())
        );
    }

    @Test
    public void physicalDeviceSerialIsRejectedAtIssueTime() {
        TestIdentity physical = withIdentity("R58M123ABC", FINGERPRINT, APP_SIGNING,
            CALLER_SIGNING, MARKER, "debug");

        TestControlException rejected = assertThrows(
            TestControlException.class,
            () -> authority.issue(EnumSet.of(TestScope.STATUS_READ), physical, TTL)
        );

        assertEquals(TestControlError.EMULATOR_REQUIRED, rejected.error());
    }

    @Test
    public void serialDriftIsRejectedAndConsumesToken() {
        assertIdentityMismatch(
            withIdentity("emulator-5556", FINGERPRINT, APP_SIGNING,
                CALLER_SIGNING, MARKER, "debug"),
            TestControlError.SERIAL_MISMATCH
        );
    }

    @Test
    public void avdFingerprintDriftIsRejectedAndConsumesToken() {
        assertIdentityMismatch(
            withIdentity(SERIAL, "082695b5aabb55e9a57136f949d3dc6a9df634b6ae943ef79dd580b5a1f0f86f", APP_SIGNING,
                CALLER_SIGNING, MARKER, "debug"),
            TestControlError.AVD_FINGERPRINT_MISMATCH
        );
    }

    @Test
    public void fingerprintOutsideN31AllowlistIsRejectedAtIssueTime() {
        TestIdentity unknown = withIdentity(
            SERIAL,
            "c".repeat(64),
            APP_SIGNING,
            CALLER_SIGNING,
            MARKER,
            "debug"
        );

        assertEquals(
            TestControlError.AVD_FINGERPRINT_MISMATCH,
            assertThrows(
                TestControlException.class,
                () -> authority.issue(EnumSet.of(TestScope.STATUS_READ), unknown, TTL)
            ).error()
        );
    }

    @Test
    public void appSigningDriftIsRejectedAndConsumesToken() {
        assertIdentityMismatch(
            withIdentity(SERIAL, FINGERPRINT, "c".repeat(64),
                CALLER_SIGNING, MARKER, "debug"),
            TestControlError.APP_SIGNING_MISMATCH
        );
    }

    @Test
    public void untrustedInstrumentationSigningIsRejectedAtIssueTime() {
        TestIdentity untrusted = withIdentity(
            SERIAL,
            FINGERPRINT,
            APP_SIGNING,
            "c".repeat(64),
            MARKER,
            "debug"
        );

        assertEquals(
            TestControlError.CALLER_SIGNING_MISMATCH,
            assertThrows(
                TestControlException.class,
                () -> authority.issue(EnumSet.of(TestScope.STATUS_READ), untrusted, TTL)
            ).error()
        );
    }

    @Test
    public void instrumentationSigningDriftIsRejectedAndConsumesToken() {
        assertIdentityMismatch(
            withIdentity(SERIAL, FINGERPRINT, APP_SIGNING,
                "c".repeat(64), MARKER, "debug"),
            TestControlError.CALLER_SIGNING_MISMATCH
        );
    }

    @Test
    public void wrongMarkerIsRejectedAndConsumesToken() {
        assertIdentityMismatch(
            withIdentity(SERIAL, FINGERPRINT, APP_SIGNING,
                CALLER_SIGNING, "NOT_A_TEST_DEVICE", "debug"),
            TestControlError.TEST_MARKER_MISMATCH
        );
    }

    @Test
    public void releaseVariantIsRejectedAtIssueAndAuthorizeTime() {
        TestIdentity release = withIdentity(SERIAL, FINGERPRINT, APP_SIGNING,
            CALLER_SIGNING, MARKER, "release");
        assertEquals(
            TestControlError.DEBUG_BUILD_REQUIRED,
            assertThrows(
                TestControlException.class,
                () -> authority.issue(EnumSet.of(TestScope.STATUS_READ), release, TTL)
            ).error()
        );

        IssuedTestToken issued = authority.issue(
            EnumSet.of(TestScope.STATUS_READ),
            trustedIdentity(),
            TTL
        );
        assertEquals(
            TestControlError.DEBUG_BUILD_REQUIRED,
            assertThrows(
                TestControlException.class,
                () -> authority.authorize(issued.value(), TestScope.STATUS_READ, release)
            ).error()
        );
    }

    @Test
    public void malformedAndUnknownTokensFailClosedWithoutEchoingSecret() {
        String unknown = "secret-test-token-that-must-not-be-echoed";

        TestControlException malformed = assertThrows(
            TestControlException.class,
            () -> authority.authorize("", TestScope.STATUS_READ, trustedIdentity())
        );
        TestControlException rejected = assertThrows(
            TestControlException.class,
            () -> authority.authorize(unknown, TestScope.STATUS_READ, trustedIdentity())
        );

        assertEquals(TestControlError.TOKEN_INVALID, malformed.error());
        assertEquals(TestControlError.TOKEN_INVALID, rejected.error());
        assertFalse(rejected.getMessage().contains(unknown));
    }

    @Test
    public void issuedValueAndAuthorityDiagnosticsNeverExposeToken() {
        IssuedTestToken issued = authority.issue(
            EnumSet.allOf(TestScope.class),
            trustedIdentity(),
            TTL
        );

        assertTrue(issued.value().length() >= 43);
        assertFalse(issued.toString().contains(issued.value()));
        assertFalse(authority.toString().contains(issued.value()));
    }

    @Test
    public void digestCollisionRetriesWithoutOverwritingExistingToken() {
        TestTokenAuthority colliding = new TestTokenAuthority(
            clock,
            new SequenceSecureRandom(0, 0, 1)
        );
        IssuedTestToken first = colliding.issue(
            EnumSet.of(TestScope.STATUS_READ),
            trustedIdentity(),
            TTL
        );
        IssuedTestToken second = colliding.issue(
            EnumSet.of(TestScope.SELF_CHECK_READ),
            trustedIdentity(),
            TTL
        );

        assertFalse(first.value().equals(second.value()));
        assertEquals(
            TestScope.STATUS_READ,
            colliding.authorize(
                first.value(),
                TestScope.STATUS_READ,
                trustedIdentity()
            ).scope()
        );
        assertEquals(
            TestScope.SELF_CHECK_READ,
            colliding.authorize(
                second.value(),
                TestScope.SELF_CHECK_READ,
                trustedIdentity()
            ).scope()
        );
    }

    @Test
    public void repeatedDigestCollisionFailsWithoutOverwritingExistingToken() {
        TestTokenAuthority colliding = new TestTokenAuthority(
            clock,
            new SequenceSecureRandom(0)
        );
        IssuedTestToken first = colliding.issue(
            EnumSet.of(TestScope.STATUS_READ),
            trustedIdentity(),
            TTL
        );

        assertEquals(
            TestControlError.TOKEN_GENERATION_FAILED,
            assertThrows(
                TestControlException.class,
                () -> colliding.issue(
                    EnumSet.of(TestScope.SELF_CHECK_READ),
                    trustedIdentity(),
                    TTL
                )
            ).error()
        );
        assertEquals(
            TestScope.STATUS_READ,
            colliding.authorize(
                first.value(),
                TestScope.STATUS_READ,
                trustedIdentity()
            ).scope()
        );
    }

    @Test
    public void replayRecordsExpireAndRevokeClearsAllAuthorityState() {
        IssuedTestToken consumed = authority.issue(
            EnumSet.of(TestScope.STATUS_READ),
            trustedIdentity(),
            TTL
        );
        authority.authorize(consumed.value(), TestScope.STATUS_READ, trustedIdentity());
        clock.advance(Duration.ofMinutes(11));
        assertEquals(
            TestControlError.TOKEN_INVALID,
            assertThrows(
                TestControlException.class,
                () -> authority.authorize(
                    consumed.value(),
                    TestScope.STATUS_READ,
                    trustedIdentity()
                )
            ).error()
        );

        IssuedTestToken active = authority.issue(
            EnumSet.of(TestScope.STATUS_READ),
            trustedIdentity(),
            TTL
        );
        authority.revokeAll();
        assertEquals(
            TestControlError.TOKEN_INVALID,
            assertThrows(
                TestControlException.class,
                () -> authority.authorize(
                    active.value(),
                    TestScope.STATUS_READ,
                    trustedIdentity()
                )
            ).error()
        );
        assertTrue(authority.toString().contains("active=0"));
        assertTrue(authority.toString().contains("expired=0"));
        assertTrue(authority.toString().contains("consumed=0"));
    }

    private void assertIdentityMismatch(TestIdentity actual, TestControlError expected) {
        IssuedTestToken issued = authority.issue(
            EnumSet.of(TestScope.STATUS_READ),
            trustedIdentity(),
            TTL
        );
        TestControlException rejected = assertThrows(
            TestControlException.class,
            () -> authority.authorize(issued.value(), TestScope.STATUS_READ, actual)
        );
        assertEquals(expected, rejected.error());
        assertEquals(TestControlError.TOKEN_REPLAYED, replayError(issued, trustedIdentity()));
    }

    private TestControlError replayError(IssuedTestToken issued, TestIdentity identity) {
        return assertThrows(
            TestControlException.class,
            () -> authority.authorize(issued.value(), TestScope.STATUS_READ, identity)
        ).error();
    }

    private static TestIdentity trustedIdentity() {
        return withIdentity(SERIAL, FINGERPRINT, APP_SIGNING, CALLER_SIGNING, MARKER, "debug");
    }

    private static TestIdentity withIdentity(
        String serial,
        String fingerprint,
        String appSigning,
        String callerSigning,
        String marker,
        String variant
    ) {
        return new TestIdentity(
            serial,
            fingerprint,
            appSigning,
            callerSigning,
            marker,
            variant
        );
    }

    private static final class DeterministicSecureRandom extends SecureRandom {
        private int counter;

        @Override
        public void nextBytes(byte[] bytes) {
            byte[] seed = ("test-token-" + counter++).getBytes(StandardCharsets.UTF_8);
            for (int index = 0; index < bytes.length; index += 1) {
                bytes[index] = seed[index % seed.length];
            }
            Arrays.fill(seed, (byte) 0);
        }
    }

    private static final class SequenceSecureRandom extends SecureRandom {
        private final int[] sequence;
        private int index;

        private SequenceSecureRandom(int... sequence) {
            this.sequence = sequence.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            int value = sequence[Math.min(index, sequence.length - 1)];
            index += 1;
            Arrays.fill(bytes, (byte) value);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
