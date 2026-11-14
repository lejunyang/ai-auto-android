import android.app.UiAutomation;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * 功能用途：仅为固定模拟器构造主屏输入基线，使用 UiAutomation 聚焦被标题栏遮挡的 fixture 输入框。
 * 安全约束：目标显示、包名、resource ID 与动作均固定；没有任意节点、命令或主屏配置接口。
 */
public final class PrimaryInputFocus {
    public static void main(String[] args) throws Exception {
        Class<?> properties = Class.forName("android.os.SystemProperties");
        var get = properties.getMethod("get", String.class);
        if (args.length != 0 || !"1".equals(get.invoke(null, "ro.kernel.qemu"))
                || !"google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys".equals(get.invoke(null, "ro.build.fingerprint")))
            throw new SecurityException("fixed emulator only");
        HandlerThread thread = new HandlerThread("primary-input-test");
        thread.start();
        UiAutomation automation = null;
        try {
            Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation = (UiAutomation) UiAutomation.class.getConstructor(Looper.class,
                    Class.forName("android.app.IUiAutomationConnection")).newInstance(thread.getLooper(), connection);
            UiAutomation.class.getMethod("connect", int.class).invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            var info = automation.getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            List<AccessibilityNodeInfo> inputs = new ArrayList<>();
            var windows = automation.getWindowsOnAllDisplays().get(0);
            if (windows == null) throw new IllegalStateException("missing primary windows");
            for (var window : windows) {
                var root = window.getRoot();
                if (root != null) inputs.addAll(root.findAccessibilityNodeInfosByViewId("dev.aiauto.fixture:id/text_input"));
            }
            if (inputs.size() != 1 || !"dev.aiauto.fixture".contentEquals(inputs.get(0).getPackageName()))
                throw new IllegalStateException("primary fixture input not unique");
            var input = inputs.get(0);
            boolean focus = input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            boolean click = input.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            System.out.println("focus=" + focus + " click=" + click);
            if (!focus || !click) throw new IllegalStateException("framework focus/click refused");
        } finally {
            if (automation != null) UiAutomation.class.getMethod("disconnect").invoke(automation);
            thread.quitSafely();
        }
    }
}
