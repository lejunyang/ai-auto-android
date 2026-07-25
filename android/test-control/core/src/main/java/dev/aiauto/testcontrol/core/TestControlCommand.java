package dev.aiauto.testcontrol.core;

/**
 * 功能用途：限定 debug 控制面可执行的固定命令，避免字符串方法名扩展为任意操作。
 */
public enum TestControlCommand {
    STATUS,
    ACCESSIBILITY_ENABLE,
    ACCESSIBILITY_DISABLE,
    BRIDGE_PAIRING_STATE,
    SCRIPT_PRESET,
    SELF_CHECK
}
