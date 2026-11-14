package dev.aiauto.displayd;

/** 功能用途：隔离身份、版本、锁定与资源预算规则，使安全边界可在普通 JVM 上验证。 */
final class Policy {
    static final int MAX_MESSAGE_BYTES = 16384;
    static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    static final int MAX_DISPLAYS = 2;

    static boolean acceptsUid(int uid) { return uid == 0 || uid == 2000; }
    static void dimensions(int width, int height, int dpi) throws Protocol.Failure {
        if (width < 128 || width > 2048 || height < 128 || height > 2048 || dpi < 72 || dpi > 640)
            throw new Protocol.Failure("INVALID_ARGUMENT", "width/height: 128..2048; dpi: 72..640");
    }
    static void capacity(int count) throws Protocol.Failure {
        if (count >= MAX_DISPLAYS) throw new Protocol.Failure("DISPLAY_LIMIT", "at most 2 displays");
    }
    static void frameSize(long bytes) throws Protocol.Failure {
        if (bytes < 1 || bytes > MAX_FRAME_BYTES)
            throw new Protocol.Failure("FRAME_TOO_LARGE", "PNG exceeds frame budget");
    }
    static int flags(int api) throws Protocol.Failure {
        if (api < 33) throw new Protocol.Failure("UNSUPPORTED_API", "requires API 33+");
        // 固定排除 SECURE(1<<2)；独立焦点和设备显示组仅在 API 34+ 存在。
        int base = 1 | (1 << 1) | (1 << 3) | (1 << 6) | (1 << 7) | (1 << 8)
                | (1 << 10) | (1 << 11) | (1 << 12) | (1 << 13);
        return api >= 34 ? base | (1 << 14) | (1 << 15) : base;
    }
    static boolean shouldKeepAwake(boolean credentialLocked, int count) {
        return !credentialLocked && count > 0;
    }
    static boolean credentialLocked(boolean secure, boolean locked) { return secure && locked; }
    static void captureAllowed(boolean credentialLocked) throws Protocol.Failure {
        if (credentialLocked) throw new Protocol.Failure("DEVICE_LOCKED", "device is credential locked");
    }
    // 锁定优先；API 33 无法查组状态，只能拒绝已知非 ON 的屏，不能声称组已唤醒。
    static void captureAllowed(boolean credentialLocked, Boolean groupAwake, int state) throws Protocol.Failure {
        captureAllowed(credentialLocked);
        if (Boolean.FALSE.equals(groupAwake) || state != 2)
            throw new Protocol.Failure("DISPLAY_ASLEEP", "display group is asleep; close and recreate the display (destroys its apps)");
    }
    // 拒绝单个客户端的清理失败不能扩大成整个辅助进程退出。
    static void closeRejected(java.io.Closeable client) {
        try { client.close(); }
        catch (java.io.IOException ignored) { /* 对端异常仅影响当前连接。 */ }
    }
}
