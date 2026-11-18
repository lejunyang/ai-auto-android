package dev.aiauto.displayd;

import android.app.KeyguardManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 功能用途：封装固定的 Android 框架调用，管理虚拟屏所有权、锁定边界和取帧生命周期。 */
final class AndroidBackend implements AutoCloseable {
    record Reply(Map<String, Object> result, byte[] png) {}
    private final Map<Integer, Screen> screens = new LinkedHashMap<>();
    // 创建失败的意外活动流也必须保留引用，防止GC/Binder死亡隐式注销；通过shutdown重试。
    private final List<Screen> pendingCreations = new ArrayList<>();
    private final HandlerThread frames = new HandlerThread("displayd-frames");
    private Context context;
    private DisplayManager displays;
    private Object power;
    private Method userActivity;
    private Method interactive;
    private String keepAwakeError;
    private long lastActivityUptimeMs;
    private boolean closed;
    private final int cameraAssociation;
    private final int cameraUser;
    private Context cameraContext;

    AndroidBackend(int cameraAssociation, int cameraUser) { this.cameraAssociation = cameraAssociation; this.cameraUser = cameraUser; frames.start(); }

    // 本 APK 不安装，运行于 shell app_process；包归属修复只用于此受限路径，失败返回不可用，不改隐藏 API 策略。
    @android.annotation.SuppressLint({"BlockedPrivateApi", "SoonBlockedPrivateApi"})
    private Context cameraContext() throws Exception {
        if (cameraContext != null) return cameraContext;
        initialize();
        // 只有摄像头路径需要真实 shell 包上下文；普通 DisplayManager 初始化保持原契约。
        Context base = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        Class<?> impl = Class.forName("android.app.ContextImpl");
        var op = impl.getDeclaredField("mOpPackageName"); op.setAccessible(true); op.set(base, "com.android.shell");
        var attribution = impl.getDeclaredField("mAttributionSource"); attribution.setAccessible(true);
        attribution.set(base, new AttributionSource.Builder(2000).setPackageName("com.android.shell").build());
        cameraContext = new ShellContext(base);
        android.app.Application application = new android.app.Application();
        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        attach.setAccessible(true); attach.invoke(application, cameraContext);
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("currentActivityThread").invoke(null);
        var initial = at.getDeclaredField("mInitialApplication"); initial.setAccessible(true); initial.set(thread, application);
        return cameraContext;
    }

    // 只允许固定的内部反射，不接受调用方提供类名、方法名或参数；包名校验需与 shell uid 对齐。
    private void initialize() throws Exception {
        if (displays != null) return;
        // ActivityThread 内部 Handler 绑定当前 Looper；必须在已准备好的主线程构造，不能在 socket worker 构造。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            var task = new java.util.concurrent.FutureTask<Void>(() -> { initialize(); return null; });
            new Handler(Looper.getMainLooper()).post(task);
            try { task.get(3, java.util.concurrent.TimeUnit.SECONDS); }
            finally { task.cancel(false); }
            return;
        }
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = threadClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object thread = ctor.newInstance();
        var current = threadClass.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true); current.set(null, thread);
        Context base = (Context) threadClass.getDeclaredMethod("getSystemContext").invoke(thread);
        context = new ShellContext(base);
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class).invoke(null, "power");
        Class<?> powerInterface = Class.forName("android.os.IPowerManager");
        power = Class.forName("android.os.IPowerManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        userActivity = powerInterface.getMethod("userActivity", int.class, long.class, int.class, int.class);
        if (Build.VERSION.SDK_INT >= 34)
            interactive = powerInterface.getMethod("isDisplayInteractive", int.class);
        var manager = DisplayManager.class.getDeclaredConstructor(Context.class);
        manager.setAccessible(true);
        displays = manager.newInstance(context);
    }

    private boolean credentialLocked() throws Protocol.Failure {
        try {
            initialize();
            int user = (Integer) Class.forName("android.app.ActivityManager")
                    .getMethod("getCurrentUser").invoke(null);
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            boolean secure = (Boolean) KeyguardManager.class.getMethod("isDeviceSecure", int.class)
                    .invoke(keyguard, user);
            boolean locked = (Boolean) KeyguardManager.class.getMethod("isDeviceLocked", int.class)
                    .invoke(keyguard, user);
            return Policy.credentialLocked(secure, locked);
        } catch (Exception error) {
            throw new Protocol.Failure("LOCK_STATE_UNAVAILABLE", "cannot determine foreground user credential lock");
        }
    }

    Reply execute(Protocol.Request request) throws Exception {
        // 在排队进入全局锁前开始计时，多个关闭请求不得各自再追加完整六秒等待。
        long releaseDeadline = CameraRelease.deadline();
        synchronized (this) {
        if (closed) throw new Protocol.Failure("SHUTTING_DOWN", "helper is stopping");
        Map<String, Object> result;
        switch (request.method()) {
            case "hello" -> result = Protocol.fields("protocolVersion", 2, "apiLevel", Build.VERSION.SDK_INT,
                    "supported", Build.VERSION.SDK_INT >= 33, "virtualCameraUser", cameraUser,
                    "capabilities", Protocol.fields("virtualDisplay", Build.VERSION.SDK_INT >= 33,
                            "binaryPng", true, "credentialLockGuard", true,
                            "groupAwakeQuery", Build.VERSION.SDK_INT >= 34,
                            "focusIsolation", Build.VERSION.SDK_INT >= 34,
                            "virtualCamera", cameraAssociation > 0),
                    "limits", Protocol.fields("maxMessageBytes", Policy.MAX_MESSAGE_BYTES,
                            "maxFrameBytes", Policy.MAX_FRAME_BYTES, "maxDisplays", Policy.MAX_DISPLAYS,
                            "minDimension", 128, "maxDimension", 2048, "minDpi", 72, "maxDpi", 640));
            case "display.create" -> {
                int flags = Policy.flags(Build.VERSION.SDK_INT, request.focus());
                Policy.dimensions(request.width(), request.height(), request.dpi());
                Policy.capacity(screens.size() + pendingCreations.size());
                initialize();
                Screen screen = new Screen(request.width(), request.height(), request.dpi(), request.focus());
                try {
                    if (request.camera().isEmpty()) {
                        screen.display = displays.createVirtualDisplay("aactl-displayd", screen.width, screen.height,
                                screen.dpi, screen.reader.getSurface(), flags);
                    } else {
                        CameraPolicy.requireAvailable(cameraAssociation > 0, Build.VERSION.SDK_INT);
                        Policy.captureAllowed(credentialLocked());
                        screen.camera = new VirtualCameraBackend();
                        try {
                            screen.display = screen.camera.create(cameraContext(), cameraAssociation, screen.width, screen.height,
                                    screen.dpi, screen.reader.getSurface(), flags, releaseDeadline);
                        } catch (Protocol.Failure error) { throw error; }
                        catch (Exception error) {
                            while (error.getCause() instanceof Exception) error = (Exception) error.getCause();
                            String reason = String.valueOf(error.getMessage()).replaceAll("[\\r\\n]", " ");
                            throw new Protocol.Failure("VIRTUAL_CAMERA_UNAVAILABLE", "API " + Build.VERSION.SDK_INT
                                    + ": shell context initialization failed: " + error.getClass().getSimpleName()
                                    + ": " + reason.substring(0, Math.min(500, reason.length())));
                        }
                    }
                    if (screen.display == null) throw new IllegalStateException("null virtual display");
                    int id = screen.display.getDisplay().getDisplayId();
                    if (screen.camera != null) screen.actualFlags = screen.display.getDisplay().getFlags();
                    screens.put(id, screen);
                    keepAwake();
                    result = screen.describe(id, false);
                } catch (Exception error) {
                    try { screen.close(releaseDeadline); }
                    catch (Exception cleanup) {
                        if (screen.camera != null && !screens.containsValue(screen)) pendingCreations.add(screen);
                        cleanup.addSuppressed(error); throw cleanup;
                    }
                    screens.values().remove(screen);
                    throw error;
                }
            }
            case "display.list" -> result = Protocol.fields("displays", describe(false));
            case "camera.setImage" -> {
                CameraPolicy.path(request.path());
                try {
                    CameraPolicy.requireAvailable(cameraAssociation > 0, Build.VERSION.SDK_INT);
                    Policy.captureAllowed(credentialLocked());
                    Screen screen = owned(request.displayId());
                    if (screen.releasePending) throw CameraRelease.busy();
                    if (screen.camera == null) throw new Protocol.Failure("VIRTUAL_CAMERA_NOT_ENABLED", "Display has no camera; create a new display with --camera back");
                    screen.camera.setImage(request.path());
                    result = Protocol.fields("displayId", request.displayId(), "imageSet", true);
                } finally {
                    try { android.system.Os.remove(request.path()); }
                    catch (Exception ignored) { /* 即使所有权或锁定检查失败也清理本次图片。 */ }
                }
            }
            case "display.close" -> {
                Screen screen = owned(request.displayId());
                // BUSY时保留原编号和容量；显示已移除，只有相机资源仍待回调排空。
                if (screen.camera == null) screens.remove(request.displayId());
                screen.close(releaseDeadline); screens.remove(request.displayId());
                result = Protocol.fields("displayId", request.displayId(), "closed", true);
            }
            case "display.capture" -> {
                Screen screen = owned(request.displayId());
                if (screen.releasePending) throw CameraRelease.busy();
                captureAllowed(request.displayId());
                byte[] png = screen.capture();
                // 编码期间设备可能锁定或显示组休眠，再检查一次；错误响应从不携带帧体。
                captureAllowed(request.displayId());
                return new Reply(Protocol.fields("displayId", request.displayId(), "width", screen.width,
                        "height", screen.height, "format", "png", "bytes", png.length), png);
            }
            case "status" -> result = Protocol.fields("credentialLocked", credentialLocked(),
                    "displays", describe(true), "keepAwakeIntervalMs", 2000,
                    "pendingCameraReleases", pendingCreations.size(),
                    "lastActivityUptimeMs", lastActivityUptimeMs, "keepAwakeError", keepAwakeError);
            case "shutdown" -> { close(releaseDeadline); result = Protocol.fields("stopped", true); }
            default -> throw new Protocol.Failure("UNKNOWN_METHOD", "method is not supported");
        }
        return new Reply(result, null);
        }
    }

    private Screen owned(int id) throws Protocol.Failure {
        Screen screen = screens.get(id);
        if (screen == null) throw new Protocol.Failure("DISPLAY_NOT_FOUND", "display is not owned by this helper");
        return screen;
    }

    private void captureAllowed(int id) throws Exception {
        // 先查凭据锁，锁定时不触碰任何电源操作；查询失败也不得发送帧。
        Policy.captureAllowed(credentialLocked());
        Display current = displays.getDisplay(id);
        if (current == null || !current.isValid())
            throw new Protocol.Failure("DISPLAY_NOT_FOUND", "display was removed by system");
        Boolean awake = interactive == null ? null : (Boolean) interactive.invoke(power, id);
        Policy.captureAllowed(credentialLocked(), awake, current.getState());
    }

    private List<Object> describe(boolean status) throws Exception {
        List<Object> list = new ArrayList<>();
        for (var entry : screens.entrySet()) list.add(entry.getValue().describe(entry.getKey(), status));
        return list;
    }

    synchronized void keepAwake() {
        if (closed || screens.isEmpty()) return;
        try {
            if (!Policy.shouldKeepAwake(credentialLocked(), screens.size())) return;
            for (int id : screens.keySet()) {
                if (screens.get(id).releasePending) continue;
                if (credentialLocked()) return;
                userActivity.invoke(power, id, SystemClock.uptimeMillis(), 0, 0);
                lastActivityUptimeMs = SystemClock.uptimeMillis();
            }
            keepAwakeError = null;
        } catch (Exception error) {
            keepAwakeError = error instanceof Protocol.Failure ? ((Protocol.Failure) error).code : "FRAMEWORK_ERROR";
        }
    }

    @Override public void close() throws Exception { close(CameraRelease.deadline()); }

    private synchronized void close(long deadline) throws Exception {
        if (closed) return;
        List<CameraRelease.CloseAction> releases = new ArrayList<>();
        for (var entry : new ArrayList<>(screens.entrySet())) {
            releases.add(until -> {
                Screen screen = entry.getValue();
                if (screen.camera == null) {
                    // 普通屏保留原shutdown的逐屏尽力释放行为；摄像头失败则严格保留所有权。
                    try { screen.close(until); }
                    catch (RuntimeException error) { System.err.println("displayd release failed: " + error); }
                } else screen.close(until);
                screens.remove(entry.getKey());
            });
        }
        for (Screen screen : new ArrayList<>(pendingCreations)) {
            releases.add(until -> { screen.close(until); pendingCreations.remove(screen); });
        }
        CameraRelease.closeAll(releases, deadline, () -> { closed = true; frames.quitSafely(); });
    }

    private final class Screen implements AutoCloseable {
        final int width, height, dpi;
        final String focus;
        final ImageReader reader;
        VirtualDisplay display;
        VirtualCameraBackend camera;
        private Image latest;
        private boolean released;
        private boolean displayReleased, releasePending;
        private int actualFlags;
        Screen(int width, int height, int dpi, String focus) {
            this.width = width; this.height = height; this.dpi = dpi;
            this.focus = focus;
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(source -> {
                synchronized (this) {
                    if (released) return;
                    Image image = source.acquireLatestImage();
                    if (image == null) return;
                    if (latest != null) latest.close();
                    latest = image;
                }
            }, new Handler(frames.getLooper()));
        }
        Map<String, Object> describe(int id, boolean status) throws Exception {
            Display current = display.getDisplay();
            Map<String, Object> description = Protocol.fields("displayId", id, "width", width,
                    "height", height, "dpi", dpi, "focus", focus,
                    "focusIsolated", Policy.focusIsolated(Build.VERSION.SDK_INT, focus));
            if (camera != null) {
                description.put("camera", "back");
                Map<String, Object> cameraInfo = camera.describe();
                cameraInfo.put("releasePending", releasePending);
                description.put("cameraInfo", cameraInfo);
                description.put("displayFlags", actualFlags);
                // 读取系统附加的实际标志，不把 VirtualDevice 的结果套用普通屏推断。
                description.put("focusIsolated", (actualFlags & FrameworkDisplayFlags.DisplayResult.STEAL_TOP_FOCUS_DISABLED) != 0
                        && (actualFlags & FrameworkDisplayFlags.DisplayResult.OWN_FOCUS) != 0);
            }
            if (status) {
                if (releasePending) {
                    // 显示已销毁，不再反射失效DisplayInfo或用旧显示编号发电源查询。
                    description.put("state", Display.STATE_UNKNOWN);
                    description.put("displayGroupId", -1);
                    description.put("groupAwake", null);
                    return description;
                }
                Class<?> infoClass = Class.forName("android.view.DisplayInfo");
                Object info = infoClass.getConstructor().newInstance();
                boolean valid = (Boolean) Display.class.getMethod("getDisplayInfo", infoClass).invoke(current, info);
                if (!valid) throw new Protocol.Failure("DISPLAY_NOT_FOUND", "display was removed by system");
                int groupId = info.getClass().getField("displayGroupId").getInt(info);
                description.put("state", current.getState());
                description.put("displayGroupId", groupId);
                if (camera != null) {
                    // 只读系统真实移除策略，不能把创建请求中的销毁标志当作已生效证据。
                    try { description.put("removeMode", infoClass.getField("removeMode").getInt(info)); }
                    catch (ReflectiveOperationException error) { description.put("removeMode", null); }
                }
                // API 33 没有按显示组查询的 binder API；未知值必须如实返回 null，不能借用主屏状态。
                description.put("groupAwake", interactive == null ? null : interactive.invoke(power, id));
            }
            return description;
        }
        synchronized byte[] capture() throws Protocol.Failure {
            if (latest == null) throw new Protocol.Failure("FRAME_NOT_READY", "no frame available yet");
            Bitmap padded = null, cropped = null;
            try {
                Image.Plane plane = latest.getPlanes()[0];
                if (plane.getPixelStride() != 4) throw new IllegalStateException("unexpected pixel stride");
                int stride = plane.getRowStride() / 4;
                padded = Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888);
                // 最后一行可能没有 padding，逐行拷贝可避免读越界或依赖厂商填充行为。
                ByteBuffer source = plane.getBuffer().duplicate();
                ByteBuffer pixels = ByteBuffer.allocate(stride * height * 4);
                for (int row = 0; row < height; row++) {
                    source.position(row * plane.getRowStride());
                    source.limit(row * plane.getRowStride() + width * 4);
                    pixels.position(row * plane.getRowStride()); pixels.put(source);
                    source.limit(plane.getBuffer().limit());
                }
                pixels.rewind(); padded.copyPixelsFromBuffer(pixels);
                cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
                FrameOutput output = new FrameOutput();
                if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output))
                    throw new Protocol.Failure("CAPTURE_FAILED", "PNG encoding failed");
                Policy.frameSize(output.size());
                return output.toByteArray();
            } catch (FrameOutput.TooLarge error) {
                throw new Protocol.Failure("FRAME_TOO_LARGE", "PNG exceeds frame budget");
            } finally {
                if (cropped != null && cropped != padded) cropped.recycle();
                if (padded != null) padded.recycle();
            }
        }
        @Override public void close() throws Exception { close(CameraRelease.deadline()); }
        void close(long deadline) throws Exception {
            if (released) return;
            if (camera == null) {
                // 普通DisplayManager屏继续使用既有立即释放路径，不引入相机排空状态。
                synchronized (this) {
                    released = true;
                    try { if (display != null) display.release(); }
                    finally {
                        try { if (latest != null) { latest.close(); latest = null; } }
                        finally { reader.close(); }
                    }
                }
                return;
            }
            releasePending = true;
            if (!displayReleased && display != null) { display.release(); displayReleased = true; }
            // display释放销毁Activity时仍继续出帧；只有HAL确认全部流关闭后才注销camera/device。
            if (camera != null) camera.close(deadline);
            synchronized (this) {
                released = true;
                try { if (latest != null) { latest.close(); latest = null; } }
                finally { reader.close(); }
            }
        }
    }

    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
        }
        @Override public int getDeviceId() { return 0; }
        @Override public Context getApplicationContext() { return this; }
    }
}
