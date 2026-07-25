package dev.aiauto.testcontrol.core;

/**
 * 功能用途：签发仅存摘要的一次性短期令牌，并在副作用前校验完整模拟器测试身份。
 */

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class TestTokenAuthority {
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_GENERATION_ATTEMPTS = 8;
    private static final int MAX_ACTIVE_TOKENS = 4_096;
    private static final int MAX_EXPIRED_TOKENS = 4_096;
    private static final int MAX_CONSUMED_TOKENS = 4_096;
    private static final Duration MAX_TTL = Duration.ofMinutes(5);
    private static final Duration REPLAY_RETENTION = Duration.ofMinutes(10);
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Map<DigestKey, ActiveToken> activeTokens = new HashMap<>();
    private final Map<DigestKey, Instant> expiredTokens = new HashMap<>();
    private final Map<DigestKey, Instant> consumedTokens = new HashMap<>();

    public TestTokenAuthority() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    public TestTokenAuthority(Clock clock, SecureRandom secureRandom) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.secureRandom = java.util.Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public synchronized IssuedTestToken issue(
        Set<TestScope> scopes,
        TestIdentity identity,
        Duration ttl
    ) {
        validateIssuance(scopes, identity, ttl);
        purgeExpiredRecords();
        if (activeTokens.size() >= MAX_ACTIVE_TOKENS) {
            throw failure(TestControlError.TOKEN_GENERATION_FAILED);
        }
        GeneratedToken generated = generateUniqueToken();
        Instant expiresAt = clock.instant().plus(ttl);
        Set<TestScope> immutableScopes = Set.copyOf(EnumSet.copyOf(scopes));
        activeTokens.put(
            generated.digest(),
            new ActiveToken(expiresAt, immutableScopes, identity)
        );
        return new IssuedTestToken(generated.value(), expiresAt, immutableScopes);
    }

    public synchronized AuthorizationGrant authorize(
        String token,
        TestScope requestedScope,
        TestIdentity actualIdentity
    ) {
        if (token == null || token.isBlank() || requestedScope == null || actualIdentity == null) {
            throw failure(TestControlError.TOKEN_INVALID);
        }
        purgeExpiredRecords();
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        DigestKey digest;
        try {
            digest = new DigestKey(sha256(tokenBytes));
        } finally {
            java.util.Arrays.fill(tokenBytes, (byte) 0);
        }
        ActiveToken active = activeTokens.remove(digest);
        if (active == null) {
            if (expiredTokens.remove(digest) != null) {
                rememberConsumed(digest);
                throw failure(TestControlError.TOKEN_EXPIRED);
            }
            if (consumedTokens.containsKey(digest)) {
                throw failure(TestControlError.TOKEN_REPLAYED);
            }
            throw failure(TestControlError.TOKEN_INVALID);
        }

        // 任何授权尝试都先记为已消费；身份或 scope 错误不能用同一令牌继续探测。
        rememberConsumed(digest);
        if (!clock.instant().isBefore(active.expiresAt())) {
            throw failure(TestControlError.TOKEN_EXPIRED);
        }
        validateIdentity(active.identity(), actualIdentity);
        if (!active.scopes().contains(requestedScope)) {
            throw failure(TestControlError.SCOPE_DENIED);
        }
        return new AuthorizationGrant(requestedScope, actualIdentity, clock.instant());
    }

    public synchronized void revokeAll() {
        activeTokens.clear();
        expiredTokens.clear();
        consumedTokens.clear();
    }

    @Override
    public synchronized String toString() {
        return "TestTokenAuthority[active=" + activeTokens.size()
            + ", expired=" + expiredTokens.size()
            + ", consumed=" + consumedTokens.size() + "]";
    }

    private GeneratedToken generateUniqueToken() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt += 1) {
            byte[] rawToken = new byte[TOKEN_BYTES];
            secureRandom.nextBytes(rawToken);
            try {
                String value = Base64.getUrlEncoder().withoutPadding().encodeToString(rawToken);
                byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                try {
                    DigestKey digest = new DigestKey(sha256(encoded));
                    if (!activeTokens.containsKey(digest)
                        && !expiredTokens.containsKey(digest)
                        && !consumedTokens.containsKey(digest)) {
                        return new GeneratedToken(value, digest);
                    }
                } finally {
                    java.util.Arrays.fill(encoded, (byte) 0);
                }
            } finally {
                java.util.Arrays.fill(rawToken, (byte) 0);
            }
        }
        throw failure(TestControlError.TOKEN_GENERATION_FAILED);
    }

    private void rememberConsumed(DigestKey digest) {
        purgeExpiredRecords();
        if (consumedTokens.size() >= MAX_CONSUMED_TOKENS) {
            DigestKey oldest = consumedTokens.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();
            consumedTokens.remove(oldest);
        }
        consumedTokens.put(digest, clock.instant().plus(REPLAY_RETENTION));
    }

    private void purgeExpiredRecords() {
        Instant now = clock.instant();
        var activeIterator = activeTokens.entrySet().iterator();
        while (activeIterator.hasNext()) {
            Map.Entry<DigestKey, ActiveToken> entry = activeIterator.next();
            if (!now.isBefore(entry.getValue().expiresAt())) {
                rememberExpired(entry.getKey());
                activeIterator.remove();
            }
        }
        expiredTokens.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
        consumedTokens.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
    }

    private void rememberExpired(DigestKey digest) {
        if (expiredTokens.size() >= MAX_EXPIRED_TOKENS) {
            DigestKey oldest = expiredTokens.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();
            expiredTokens.remove(oldest);
        }
        expiredTokens.put(digest, clock.instant().plus(REPLAY_RETENTION));
    }

    private static void validateIssuance(
        Set<TestScope> scopes,
        TestIdentity identity,
        Duration ttl
    ) {
        if (scopes == null || scopes.isEmpty()) {
            throw failure(TestControlError.SCOPE_DENIED);
        }
        if (identity == null) {
            throw failure(TestControlError.TOKEN_INVALID);
        }
        N31EmulatorGate.validateTrustedIdentity(identity);
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
            throw failure(TestControlError.TOKEN_INVALID);
        }
    }

    private static void validateIdentity(TestIdentity expected, TestIdentity actual) {
        N31EmulatorGate.validateUnchanged(expected, actual);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static TestControlException failure(TestControlError error) {
        return new TestControlException(error, "Test control request rejected: " + error.name());
    }

    private record ActiveToken(
        Instant expiresAt,
        Set<TestScope> scopes,
        TestIdentity identity
    ) {
    }

    private record GeneratedToken(String value, DigestKey digest) {
    }

    private static final class DigestKey {
        private final byte[] value;

        private DigestKey(byte[] value) {
            this.value = value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DigestKey candidate
                && MessageDigest.isEqual(value, candidate.value);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "DigestKey[<redacted>]";
        }
    }
}
