package dev.aiauto.testcontrol.core;

/**
 * 测试用途：验证控制命令到最小 scope 的固定映射，并拒绝越权、未知或含敏感脚本的请求。
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class TestControlPolicyTest {
    private final TestControlPolicy policy = new TestControlPolicy();

    @Test
    public void everyCommandMapsToOneExplicitScope() {
        assertEquals(TestScope.STATUS_READ, policy.requiredScope(TestControlCommand.STATUS));
        assertEquals(
            TestScope.ACCESSIBILITY_CONTROL,
            policy.requiredScope(TestControlCommand.ACCESSIBILITY_ENABLE)
        );
        assertEquals(
            TestScope.ACCESSIBILITY_CONTROL,
            policy.requiredScope(TestControlCommand.ACCESSIBILITY_DISABLE)
        );
        assertEquals(
            TestScope.BRIDGE_PAIRING,
            policy.requiredScope(TestControlCommand.BRIDGE_PAIRING_STATE)
        );
        assertEquals(
            TestScope.SCRIPT_PRESET,
            policy.requiredScope(TestControlCommand.SCRIPT_PRESET)
        );
        assertEquals(
            TestScope.SELF_CHECK_READ,
            policy.requiredScope(TestControlCommand.SELF_CHECK)
        );
    }

    @Test
    public void safePresetContainsNoSensitiveOrSideEffectingContent() {
        SafeScriptPreset preset = policy.validatePreset(
            new SafeScriptPreset(
                "n32-read-only-snapshot",
                List.of("dev.aiauto.fixture"),
                List.of("accessibility.snapshot"),
                false,
                false,
                false,
                false
            )
        );

        assertEquals("n32-read-only-snapshot", preset.id());
        assertFalse(preset.containsSecrets());
        assertFalse(preset.containsScreenshots());
        assertFalse(preset.containsDevicePaths());
        assertFalse(preset.hasExternalSideEffects());
    }

    @Test
    public void sensitiveOrSideEffectingPresetIsRejected() {
        for (SafeScriptPreset preset : List.of(
            unsafePreset(true, false, false, false),
            unsafePreset(false, true, false, false),
            unsafePreset(false, false, true, false),
            unsafePreset(false, false, false, true)
        )) {
            TestControlException rejected = assertThrows(
                TestControlException.class,
                () -> policy.validatePreset(preset)
            );
            assertEquals(TestControlError.UNSAFE_SCRIPT_PRESET, rejected.error());
        }
    }

    @Test
    public void presetOnlyAllowsSnapshotCapabilityAndExplicitPackage() {
        TestControlException action = assertThrows(
            TestControlException.class,
            () -> policy.validatePreset(
                new SafeScriptPreset(
                    "n32-action",
                    List.of("dev.aiauto.fixture"),
                    List.of("accessibility.action"),
                    false,
                    false,
                    false,
                    false
                )
            )
        );
        TestControlException wildcard = assertThrows(
            TestControlException.class,
            () -> policy.validatePreset(
                new SafeScriptPreset(
                    "n32-wildcard",
                    List.of("*"),
                    List.of("accessibility.snapshot"),
                    false,
                    false,
                    false,
                    false
                )
            )
        );

        assertEquals(TestControlError.UNSAFE_SCRIPT_PRESET, action.error());
        assertEquals(TestControlError.UNSAFE_SCRIPT_PRESET, wildcard.error());
    }

    @Test
    public void diagnosticsNeverIncludeTokenFields() {
        TestControlStatus status = new TestControlStatus(
            "emulator-5554",
            "debug",
            true,
            false,
            false,
            List.of("STATUS_READ", "SELF_CHECK_READ")
        );

        assertTrue(status.emulator());
        assertFalse(status.toString().toLowerCase().contains("token"));
    }

    private static SafeScriptPreset unsafePreset(
        boolean secrets,
        boolean screenshots,
        boolean paths,
        boolean sideEffects
    ) {
        return new SafeScriptPreset(
            "n32-unsafe",
            List.of("dev.aiauto.fixture"),
            List.of("accessibility.snapshot"),
            secrets,
            screenshots,
            paths,
            sideEffects
        );
    }
}
