package dev.aiauto.displayd;

import static dev.aiauto.displayd.FrameworkDisplayFlags.VirtualDisplayRequest.*;

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
    static String focus(String value) throws Protocol.Failure {
        if (!"isolated".equals(value) && !"steal".equals(value))
            throw new Protocol.Failure("INVALID_ARGUMENT", "focus must be isolated or steal");
        return value;
    }
    static boolean focusIsolated(int api, String focus) {
        return api >= 34 && "isolated".equals(focus);
    }
    static int flags(int api) throws Protocol.Failure { return flags(api, "isolated"); }
    static int flags(int api, String focus) throws Protocol.Failure {
        if (api < 33) throw new Protocol.Failure("UNSUPPORTED_API", "requires API 33+");
        focus(focus);
        // 固定排除SECURE；只把二值策略映射为固定组合，API33以focusIsolated=false如实报告降级。
        int base = PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT
                | DESTROY_CONTENT_ON_REMOVAL | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED;
        if (api >= 34) base |= OWN_FOCUS | DEVICE_DISPLAY_GROUP;
        return focusIsolated(api, focus) ? base | STEAL_TOP_FOCUS_DISABLED : base;
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
