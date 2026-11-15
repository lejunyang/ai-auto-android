package dev.aiauto.displayd;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Build;
import android.os.IBinder;
import android.system.Os;
import android.system.OsConstants;
import android.view.Surface;
import java.io.FileInputStream;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** 功能用途：集中封装 VirtualDevice 与虚拟摄像头反射，独占设备、图片和流，不接触任何应用权限。 */
final class VirtualCameraBackend implements AutoCloseable {
    private Object device, camera;
    private final Map<Integer, CameraStream> streams = new LinkedHashMap<>();
    private Bitmap image;
    private boolean closed;
    private String cameraId;
    private int deviceId;

    VirtualDisplay create(Context context, int association, int width, int height, int dpi, Surface surface, int flags) throws Protocol.Failure {
        CameraPolicy.requireAvailable(association > 0, Build.VERSION.SDK_INT);
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class).invoke(null, "virtualdevice");
            if (binder == null) throw new IllegalStateException("virtualdevice service missing");
            Class<?> api = Class.forName("android.companion.virtual.IVirtualDeviceManager");
            Object service = Class.forName("android.companion.virtual.IVirtualDeviceManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            Class<?> managerClass = Class.forName("android.companion.virtual.VirtualDeviceManager");
            var constructor = managerClass.getDeclaredConstructor(api, Context.class); constructor.setAccessible(true);
            Object manager = constructor.newInstance(service, context);
            Class<?> params = Class.forName("android.companion.virtual.VirtualDeviceParams");
            Class<?> builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams$Builder");
            Object builder = builderClass.getConstructor().newInstance();
            builderClass.getMethod("setName", String.class).invoke(builder, "aactl-camera");
            builderClass.getMethod("setDevicePolicy", int.class, int.class).invoke(builder,
                    params.getField("POLICY_TYPE_CAMERA").getInt(null), params.getField("DEVICE_POLICY_CUSTOM").getInt(null));
            device = managerClass.getMethod("createVirtualDevice", int.class, params)
                    .invoke(manager, association, builderClass.getMethod("build").invoke(builder));
            deviceId = (Integer) device.getClass().getMethod("getDeviceId").invoke(device);
            Class<?> callbackClass = Class.forName("android.companion.virtual.camera.VirtualCameraCallback");
            Object callback = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{callbackClass}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "onStreamConfigured" -> configure((Integer) args[0], (Surface) args[1], (Integer) args[2], (Integer) args[3]);
                    case "onStreamClosed" -> closeStream((Integer) args[0]);
                    case "hashCode" -> { return System.identityHashCode(proxy); }
                    case "equals" -> { return proxy == args[0]; }
                    case "toString" -> { return "aactl-camera-callback"; }
                    default -> { /* capture request 不触发无上限补帧，流线程保持固定预算。 */ }
                }
                return null;
            });
            Class<?> configClass = Class.forName("android.companion.virtual.camera.VirtualCameraConfig");
            Class<?> configBuilder = Class.forName("android.companion.virtual.camera.VirtualCameraConfig$Builder");
            Object config = configBuilder.getConstructor(String.class).newInstance("aactl-camera-back");
            for (int[] size : new int[][]{{640, 480}, {1280, 720}, {1920, 1080}})
                configBuilder.getMethod("addStreamConfig", int.class, int.class, int.class, int.class)
                        .invoke(config, size[0], size[1], ImageFormat.YUV_420_888, 30);
            configBuilder.getMethod("setLensFacing", int.class).invoke(config, CameraCharacteristics.LENS_FACING_BACK);
            configBuilder.getMethod("setSensorOrientation", int.class).invoke(config, 0);
            configBuilder.getMethod("setVirtualCameraCallback", Executor.class, callbackClass).invoke(config, (Executor) Runnable::run, callback);
            camera = device.getClass().getMethod("createVirtualCamera", configClass)
                    .invoke(device, configBuilder.getMethod("build").invoke(config));
            cameraId = String.valueOf(camera.getClass().getMethod("getId").invoke(camera));
            VirtualDisplayConfig displayConfig = new VirtualDisplayConfig.Builder("aactl-displayd", width, height, dpi)
                    // 组由 VirtualDevice 分配；保留 OWN_DISPLAY_GROUP 会请求与设备分离的显示组。
                    .setSurface(surface).setFlags(flags & ~(1 << 8)).build();
            VirtualDisplay display = (VirtualDisplay) device.getClass()
                    .getMethod("createVirtualDisplay", VirtualDisplayConfig.class, Executor.class, VirtualDisplay.Callback.class)
                    .invoke(device, displayConfig, null, null);
            if (display == null) throw new IllegalStateException("null virtual display");
            // 系统附加策略必须检查实际结果；安全显示绝不作为兼容性退路。
            if ((display.getDisplay().getFlags() & android.view.Display.FLAG_SECURE) != 0) {
                display.release(); throw new IllegalStateException("system added SECURE display flag");
            }
            return display;
        } catch (Throwable error) {
            close();
            while (error.getCause() != null) error = error.getCause();
            String reason = String.valueOf(error.getMessage()).replaceAll("[\\r\\n]", " ");
            throw new Protocol.Failure("VIRTUAL_CAMERA_UNAVAILABLE", "API " + Build.VERSION.SDK_INT + ": "
                    + error.getClass().getSimpleName() + ": " + reason.substring(0, Math.min(500, reason.length())));
        }
    }

    private synchronized void configure(int id, Surface surface, int width, int height) {
        closeStream(id);
        if (closed || !CameraPolicy.acceptsStream(width, height) || streams.size() >= 3) {
            surface.release(); System.err.println("camera stream rejected: closed or outside advertised budget"); return;
        }
        CameraStream stream = new CameraStream(surface, width, height, this::image);
        streams.put(id, stream); stream.start();
    }
    private synchronized void closeStream(int id) {
        CameraStream stream = streams.remove(id);
        if (stream != null) stream.close();
    }
    // 图片由渲染线程在锁内上传到纹理，替换后才能安全回收旧位图。
    synchronized void withImage(java.util.function.Consumer<Bitmap> consumer) { consumer.accept(image); }
    private void image(java.util.function.Consumer<Bitmap> consumer) { withImage(consumer); }

    void setImage(String path) throws Protocol.Failure {
        CameraPolicy.path(path);
        Bitmap decoded = null;
        try {
            // O_NOFOLLOW 与 fstat 防止路径替换/符号链接；仅删除本次校验过的固定模式路径。
            var fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
            try (FileInputStream input = new FileInputStream(fd)) {
                var stat = Os.fstat(fd);
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size < 1 || stat.st_size > CameraPolicy.MAX_IMAGE_BYTES)
                    throw CameraPolicy.invalid("image must be a regular file <= 8 MiB");
                byte[] bytes = input.readNBytes(CameraPolicy.MAX_IMAGE_BYTES + 1);
                var options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                CameraPolicy.image(bytes.length, options.outWidth, options.outHeight, options.outMimeType);
                options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (decoded == null) throw CameraPolicy.invalid("image decoding failed");
            }
            synchronized (this) {
                if (closed) throw CameraPolicy.invalid("camera is closed");
                Bitmap previous = image; image = decoded; decoded = null;
                if (previous != null) previous.recycle();
            }
        } catch (Protocol.Failure error) { throw error; }
        catch (Exception error) { throw CameraPolicy.invalid("cannot read/decode transfer file: " + error.getClass().getSimpleName()); }
        finally {
            if (decoded != null) decoded.recycle();
            try { Os.remove(path); } catch (Exception ignored) { /* Go 侧对同一路径再做有界清理。 */ }
        }
    }

    synchronized Map<String, Object> describe() {
        var statuses = new java.util.ArrayList<Object>();
        for (var entry : streams.entrySet()) statuses.add(entry.getValue().describe(entry.getKey()));
        return Protocol.fields("cameraId", cameraId, "virtualDeviceId", deviceId, "streams", statuses);
    }
    @Override public void close() {
        Object oldCamera, oldDevice;
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (CameraStream stream : streams.values()) stream.close();
            streams.clear();
            if (image != null) { image.recycle(); image = null; }
            oldCamera = camera; oldDevice = device; camera = null; device = null;
        }
        // Binder close 可能同步回调，不能持有本对象锁等待框架。
        release(oldCamera); release(oldDevice);
    }
    private static void release(Object resource) {
        if (resource == null) return;
        try { resource.getClass().getMethod("close").invoke(resource); }
        catch (Exception e) { System.err.println("camera release failed: " + e.getClass().getSimpleName()); }
    }
}
