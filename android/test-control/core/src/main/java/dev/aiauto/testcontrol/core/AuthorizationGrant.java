package dev.aiauto.testcontrol.core;

/**
 * 功能用途：表示已完成单次令牌消费和完整身份校验的最小授权结果。
 */

import java.time.Instant;

public record AuthorizationGrant(
    TestScope scope,
    TestIdentity identity,
    Instant authorizedAt
) {
}
