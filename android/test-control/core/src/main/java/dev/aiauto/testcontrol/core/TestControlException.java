package dev.aiauto.testcontrol.core;

/**
 * 功能用途：封装失败关闭的测试控制错误，并确保消息不回显令牌或设备敏感材料。
 */
public final class TestControlException extends IllegalStateException {
    private final TestControlError error;

    public TestControlException(TestControlError error, String message) {
        super(message);
        this.error = error;
    }

    public TestControlError error() {
        return error;
    }
}
