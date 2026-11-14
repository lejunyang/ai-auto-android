import java.lang.reflect.Method;

/**
 * 功能用途：仅在固定 API35 模拟器上实验虚拟屏 IME 策略，调用集合固定且不进入产品或 MCP。
 * 安全约束：不触碰主屏、不解锁、不提供调用方指定类名方法名；原值由宿主记录并恢复。
 */
public final class FocusImePolicy {
    public static void main(String[] args) throws Exception {
        Class<?> props = Class.forName("android.os.SystemProperties");
        Method get = props.getMethod("get", String.class);
        String fp = (String) get.invoke(null, "ro.build.fingerprint");
        if (!fp.equals("google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys")
                || !"1".equals(get.invoke(null, "ro.kernel.qemu"))) throw new SecurityException("test emulator only");
        if (args.length != 2) throw new IllegalArgumentException("displayId get|local|hide|restore0|restore1|restore2");
        int id = Integer.parseInt(args[0]);
        if (id <= 0) throw new SecurityException("primary display forbidden");
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "window");
        Object window = Class.forName("android.view.IWindowManager$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        Class<?> type = Class.forName("android.view.IWindowManager");
        Method query = type.getMethod("getDisplayImePolicy", int.class);
        int before = (Integer) query.invoke(window, id);
        if (!args[1].equals("get")) {
            int policy = switch(args[1]) { case "hide", "restore1" -> 1; case "local", "restore0" -> 0; case "restore2" -> 2; default -> throw new IllegalArgumentException("unknown fixed operation"); };
            type.getMethod("setDisplayImePolicy", int.class, int.class).invoke(window, id, policy);
        }
        System.out.println("before=" + before + " after=" + query.invoke(window, id));
    }
}
