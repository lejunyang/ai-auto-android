package dev.aiauto.testcontrol.core;

/**
 * 功能用途：定义测试控制面允许签发的最小权限集合，禁止使用宽泛或隐式权限。
 */
public enum TestScope {
    STATUS_READ,
    ACCESSIBILITY_CONTROL,
    BRIDGE_PAIRING,
    SCRIPT_PRESET,
    SELF_CHECK_READ
}
