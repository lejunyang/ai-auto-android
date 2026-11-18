package dev.aiauto.displayd;

import static org.junit.Assert.*;
import org.junit.Test;

/** 测试用途：确保BUSY或任意拆除失败的shutdown请求，即使进入finally也不能触发进程退出。 */
public class ShutdownCompletionTest {
    @Test public void busyShutdownCannotExitAndNextRequestCanRetry() throws Exception {
        boolean[] exited = {false};
        ShutdownCompletion first = new ShutdownCompletion();
        try { first.run(true, () -> { throw CameraRelease.busy(); }); fail(); }
        catch (Protocol.Failure expected) { assertEquals("VIRTUAL_CAMERA_BUSY", expected.code); }
        first.finish(() -> exited[0] = true); assertFalse(exited[0]);
        ShutdownCompletion retry = new ShutdownCompletion();
        assertEquals("released", retry.run(true, () -> "released"));
        retry.finish(() -> exited[0] = true); assertTrue(exited[0]);
    }
    @Test public void normalRequestOrFrameworkFailureCannotExit() throws Exception {
        ShutdownCompletion normal = new ShutdownCompletion(); normal.run(false, () -> "hello");
        normal.finish(() -> fail("normal request must not exit"));
        ShutdownCompletion failed = new ShutdownCompletion();
        try { failed.run(true, () -> { throw new IllegalStateException("framework"); }); fail(); }
        catch (IllegalStateException expected) { assertEquals("framework", expected.getMessage()); }
        failed.finish(() -> fail("failed shutdown must not exit"));
    }
}
