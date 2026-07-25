package dev.aiauto.testcontrol.core;

/**
 * 功能用途：返回不含令牌、签名和完整指纹的测试控制状态，供设备编排安全断言。
 */

import java.util.List;

public record TestControlStatus(
    String emulatorSerial,
    String buildVariant,
    boolean emulator,
    boolean accessibilityEnabled,
    boolean bridgeRunning,
    List<String> availableScopes
) {
    public TestControlStatus {
        availableScopes = List.copyOf(availableScopes);
    }
}
