package dev.aiauto.testcontrol.core;

/**
 * 功能用途：保存 N47 当前固定场景的已验证身份与不可扩展目标包集合。
 */

import java.util.Set;

public record ProductionFixtureAuthorizationGrant(
    String scenarioId,
    Set<String> targetPackages,
    TestIdentity identity
) {
    public ProductionFixtureAuthorizationGrant {
        scenarioId = java.util.Objects.requireNonNull(scenarioId, "scenarioId");
        targetPackages = Set.copyOf(targetPackages);
        identity = java.util.Objects.requireNonNull(identity, "identity");
    }

    @Override
    public String toString() {
        return "ProductionFixtureAuthorizationGrant[scenarioId=" + scenarioId
            + ", targetPackages=" + targetPackages
            + ", identity=" + identity + "]";
    }
}
