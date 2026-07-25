package dev.aiauto.testcontrol.core;

/**
 * 功能用途：描述只读且无敏感制品的固定测试脚本元数据，供策略层在持久化前校验。
 */

import java.util.List;

public record SafeScriptPreset(
    String id,
    List<String> targetPackages,
    List<String> capabilities,
    boolean containsSecrets,
    boolean containsScreenshots,
    boolean containsDevicePaths,
    boolean hasExternalSideEffects
) {
    public SafeScriptPreset {
        id = requireText(id, "id");
        targetPackages = List.copyOf(targetPackages);
        capabilities = List.copyOf(capabilities);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
