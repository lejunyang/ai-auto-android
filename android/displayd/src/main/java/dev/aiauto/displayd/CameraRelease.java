package dev.aiauto.displayd;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 功能用途：用可注入时钟和流表条件实现有界排空，活动流存在时禁止停止出帧或注销资源。 */
final class CameraRelease {
    static final long WAIT_TIMEOUT_MS = 6000;
    static final long WAIT_TIMEOUT_NANOS = WAIT_TIMEOUT_MS * 1_000_000L;
    interface Waiter { void await(Object monitor, long nanos) throws InterruptedException; }
    interface Step { void run() throws Exception; }
    interface CloseAction { void close(long deadline) throws Exception; }
    private final Object monitor;
    private final LongSupplier clock;
    private final Waiter waiter;
    private final BooleanSupplier drained;
    private final Step stopStreams, closeCamera, closeDevice;
    private boolean pending, sealed, finalizing, released;

    CameraRelease(Object monitor, LongSupplier clock, Waiter waiter, BooleanSupplier drained,
                  Step stopStreams, Step closeCamera, Step closeDevice) {
        this.monitor = monitor; this.clock = clock; this.waiter = waiter; this.drained = drained;
        this.stopStreams = stopStreams; this.closeCamera = closeCamera; this.closeDevice = closeDevice;
    }
    static long deadline() { return System.nanoTime() + WAIT_TIMEOUT_NANOS; }
    static Protocol.Failure busy() {
        return new Protocol.Failure("VIRTUAL_CAMERA_BUSY", "Application is still using the camera. Ask the operator to release it (for example, am force-stop <pkg>), then retry close/stop. Do not kill the helper.");
    }
    boolean pending() { synchronized (monitor) { return pending; } }
    boolean acceptsStreams() { synchronized (monitor) { return !sealed && !released; } }

    void close(long deadline) throws Exception {
        synchronized (monitor) {
            if (released) return;
            pending = true;
            if (finalizing) throw busy();
            while (!drained.getAsBoolean()) {
                long remaining = deadline - clock.getAsLong();
                if (remaining <= 0) throw busy();
                try { waiter.await(monitor, remaining); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw busy(); }
            }
            // 与流表检查原子封口，等待期间允许回调/出帧，只有流空后才拒绝新配置。
            sealed = true; finalizing = true;
        }
        try {
            stopStreams.run(); closeCamera.run(); closeDevice.run();
            synchronized (monitor) { released = true; pending = false; }
        } finally { synchronized (monitor) { finalizing = false; monitor.notifyAll(); } }
    }

    static void closeAll(Iterable<CloseAction> resources, long deadline, Step complete) throws Exception {
        Exception failure = null;
        for (CloseAction resource : resources) {
            try { resource.close(deadline); }
            catch (Exception error) {
                if (failure == null) failure = error;
                else if (error instanceof Protocol.Failure && ((Protocol.Failure) error).code.equals("VIRTUAL_CAMERA_BUSY")) {
                    error.addSuppressed(failure); failure = error;
                } else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
        complete.run();
    }
}
