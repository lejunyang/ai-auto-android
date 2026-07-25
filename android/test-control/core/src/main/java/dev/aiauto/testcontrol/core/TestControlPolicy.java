package dev.aiauto.testcontrol.core;

/**
 * 功能用途：把固定控制命令映射到单一 scope，并拒绝可泄密或产生外部副作用的脚本。
 */

import java.util.Map;
import java.util.Set;

public final class TestControlPolicy {
    private static final Map<TestControlCommand, TestScope> COMMAND_SCOPES = Map.of(
        TestControlCommand.STATUS, TestScope.STATUS_READ,
        TestControlCommand.ACCESSIBILITY_ENABLE, TestScope.ACCESSIBILITY_CONTROL,
        TestControlCommand.ACCESSIBILITY_DISABLE, TestScope.ACCESSIBILITY_CONTROL,
        TestControlCommand.BRIDGE_PAIRING_STATE, TestScope.BRIDGE_PAIRING,
        TestControlCommand.SCRIPT_PRESET, TestScope.SCRIPT_PRESET,
        TestControlCommand.SELF_CHECK, TestScope.SELF_CHECK_READ
    );
    private static final Set<String> ALLOWED_CAPABILITIES = Set.of("accessibility.snapshot");
    private static final java.util.regex.Pattern PACKAGE_NAME =
        java.util.regex.Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+$");

    public TestScope requiredScope(TestControlCommand command) {
        TestScope scope = COMMAND_SCOPES.get(command);
        if (scope == null) {
            throw new TestControlException(
                TestControlError.SCOPE_DENIED,
                "Test control request rejected: SCOPE_DENIED"
            );
        }
        return scope;
    }

    public SafeScriptPreset validatePreset(SafeScriptPreset preset) {
        if (preset == null
            || !preset.id().matches("^n32-[a-z0-9-]{3,80}$")
            || preset.targetPackages().isEmpty()
            || preset.targetPackages().size() > 8
            || !preset.targetPackages().stream().allMatch(
                value -> PACKAGE_NAME.matcher(value).matches()
            )
            || preset.capabilities().isEmpty()
            || !ALLOWED_CAPABILITIES.containsAll(preset.capabilities())
            || preset.containsSecrets()
            || preset.containsScreenshots()
            || preset.containsDevicePaths()
            || preset.hasExternalSideEffects()) {
            throw new TestControlException(
                TestControlError.UNSAFE_SCRIPT_PRESET,
                "Test control request rejected: UNSAFE_SCRIPT_PRESET"
            );
        }
        return preset;
    }
}
