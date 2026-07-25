package dev.aiauto.testcontrol.core;

/**
 * 功能用途：只在签发边界返回一次测试令牌明文，并让默认诊断输出始终保持脱敏。
 */

import java.time.Instant;
import java.util.Set;

public record IssuedTestToken(
    String value,
    Instant expiresAt,
    Set<TestScope> scopes
) {
    public IssuedTestToken {
        if (value == null || value.length() < 43) {
            throw new IllegalArgumentException("A test token must contain at least 256 bits");
        }
        scopes = Set.copyOf(scopes);
    }

    @Override
    public String toString() {
        return "IssuedTestToken[value=<redacted>, expiresAt=" + expiresAt
            + ", scopes=" + scopes + "]";
    }
}
