package dev.aiauto.displayd;

/** 功能用途：定义请求与结果两套框架标志命名空间；镜像隐藏常量供纯JVM策略测试，防止跨命名空间混用。 */
final class FrameworkDisplayFlags {
    // 对应AOSP Android16 DisplayManager.VIRTUAL_DISPLAY_FLAG_*，数值不是Display.FLAG_*。
    static final class VirtualDisplayRequest {
        static final int PUBLIC = 0x00001;
        static final int PRESENTATION = 0x00002;
        static final int SECURE = 0x00004;
        static final int OWN_CONTENT_ONLY = 0x00008;
        static final int SUPPORTS_TOUCH = 0x00040;
        static final int ROTATES_WITH_CONTENT = 0x00080;
        static final int DESTROY_CONTENT_ON_REMOVAL = 0x00100;
        static final int TRUSTED = 0x00400;
        static final int OWN_DISPLAY_GROUP = 0x00800;
        static final int ALWAYS_UNLOCKED = 0x01000;
        static final int TOUCH_FEEDBACK_DISABLED = 0x02000;
        static final int OWN_FOCUS = 0x04000;
        static final int DEVICE_DISPLAY_GROUP = 0x08000;
        static final int STEAL_TOP_FOCUS_DISABLED = 0x10000;
    }
    // 对应Display.getFlags()/DisplayInfo.flags；同名项与创建请求位不同。
    static final class DisplayResult {
        static final int SECURE = 0x0002;
        static final int OWN_DISPLAY_GROUP = 0x0100;
        static final int OWN_FOCUS = 0x0800;
        static final int STEAL_TOP_FOCUS_DISABLED = 0x1000;
        static final int REMOVE_MODE_DESTROY_CONTENT = 1;
    }
}
