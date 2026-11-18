package dev.aiauto.displayd;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** 测试用途：验证活动流禁止注销、等待期间继续出帧、超时保留所有权及重复关闭恢复。 */
public class CameraReleaseTest {
    @Test public void activeStreamsTimeoutWithoutStoppingOrUnregistering() throws Exception {
        Object lock = new Object(); long[] now = {0}; boolean[] active = {true};
        List<String> events = new ArrayList<>();
        CameraRelease release = new CameraRelease(lock, () -> now[0], (monitor, nanos) -> now[0] += nanos,
                () -> !active[0], () -> events.add("stop"), () -> events.add("camera"), () -> events.add("device"));
        try { release.close(CameraRelease.WAIT_TIMEOUT_NANOS); fail("must report busy"); }
        catch (Protocol.Failure error) { assertEquals("VIRTUAL_CAMERA_BUSY", error.code); assertTrue(error.getMessage().contains("Do not kill")); }
        assertTrue(events.isEmpty()); assertTrue(release.pending()); assertTrue(release.acceptsStreams());
        active[0] = false;
        release.close(now[0] + CameraRelease.WAIT_TIMEOUT_NANOS);
        assertEquals(List.of("stop", "camera", "device"), events);
        assertFalse(release.pending()); assertFalse(release.acceptsStreams());
        release.close(now[0]); assertEquals(3, events.size());
    }
    @Test public void streamClosureBeforeDeadlinePreservesRenderingThenClosesInOrder() throws Exception {
        Object lock = new Object(); long[] now = {0}; boolean[] active = {true};
        List<String> events = new ArrayList<>();
        CameraRelease release = new CameraRelease(lock, () -> now[0], (monitor, nanos) -> {
            assertTrue(events.isEmpty()); events.add("frame"); active[0] = false; now[0] += 100;
        }, () -> !active[0], () -> events.add("stop"), () -> events.add("camera"), () -> events.add("device"));
        release.close(CameraRelease.WAIT_TIMEOUT_NANOS);
        assertEquals(List.of("frame", "stop", "camera", "device"), events);
    }
    @Test public void conditionWaitReleasesMonitorForStreamCallback() throws Exception {
        Object lock = new Object(); boolean[] active = {true};
        CameraRelease release = new CameraRelease(lock, System::nanoTime,
                (monitor, nanos) -> java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(monitor, nanos),
                () -> !active[0], () -> {}, () -> {}, () -> {});
        Thread callback = new Thread(() -> { synchronized (lock) { active[0] = false; lock.notifyAll(); } });
        synchronized (lock) { callback.start(); release.close(System.nanoTime() + CameraRelease.WAIT_TIMEOUT_NANOS); }
        callback.join(1000); assertFalse(callback.isAlive());
    }
    @Test public void shutdownSharesOneDeadlineAndDoesNotCompleteWhenBusy() throws Exception {
        List<Long> observed = new ArrayList<>(); boolean[] completed = {false};
        try {
            CameraRelease.closeAll(List.of(deadline -> { observed.add(deadline); throw CameraRelease.busy(); },
                    deadline -> observed.add(deadline)), 123L, () -> completed[0] = true);
            fail("busy shutdown must not complete");
        } catch (Protocol.Failure error) { assertEquals("VIRTUAL_CAMERA_BUSY", error.code); }
        assertEquals(List.of(123L, 123L), observed); assertFalse(completed[0]);
    }
    @Test public void interruptedWaitKeepsResourcesAndReportsBusy() throws Exception {
        List<String> events = new ArrayList<>();
        CameraRelease release = new CameraRelease(new Object(), () -> 0L, (monitor, nanos) -> { throw new InterruptedException(); },
                () -> false, () -> events.add("stop"), () -> events.add("camera"), () -> events.add("device"));
        try { release.close(CameraRelease.WAIT_TIMEOUT_NANOS); fail(); }
        catch (Protocol.Failure error) { assertEquals("VIRTUAL_CAMERA_BUSY", error.code); }
        finally { Thread.interrupted(); }
        assertTrue(events.isEmpty()); assertTrue(release.pending());
    }
    @Test public void emptyCreationRollbackClosesWithoutWaiting() throws Exception {
        List<String> events = new ArrayList<>();
        CameraRelease release = new CameraRelease(new Object(), () -> 100L, (monitor, nanos) -> fail("empty creation must not wait"),
                () -> true, () -> events.add("stop"), () -> events.add("camera"), () -> events.add("device"));
        release.close(0);
        assertEquals(List.of("stop", "camera", "device"), events);
    }
    @Test public void failedCameraCloseRetainsDeviceAndCanRetry() throws Exception {
        List<String> events = new ArrayList<>(); int[] attempts = {0};
        CameraRelease release = new CameraRelease(new Object(), () -> 0L, (monitor, nanos) -> {}, () -> true,
                () -> events.add("stop"), () -> { events.add("camera"); if (attempts[0]++ == 0) throw new IllegalStateException("binder"); },
                () -> events.add("device"));
        try { release.close(1); fail(); } catch (IllegalStateException expected) { assertTrue(release.pending()); }
        assertFalse(events.contains("device")); assertFalse(release.acceptsStreams());
        release.close(1); assertEquals(List.of("stop", "camera", "stop", "camera", "device"), events);
    }
    @Test public void unregisterRunsOutsideStreamMonitor() throws Exception {
        Object lock = new Object();
        CameraRelease release = new CameraRelease(lock, () -> 0L, (monitor, nanos) -> {}, () -> true, () -> {}, () -> {
            Thread callback = new Thread(() -> { synchronized (lock) { lock.notifyAll(); } });
            callback.start(); callback.join(1000); assertFalse("unregister blocked stream callback", callback.isAlive());
        }, () -> {});
        release.close(1);
    }
    @Test public void shutdownPrefersBusyAndPreservesAllFailures() throws Exception {
        try {
            CameraRelease.closeAll(List.of(deadline -> { throw new IllegalStateException("ordinary cleanup"); },
                    deadline -> { throw CameraRelease.busy(); }), 1, () -> fail("must not complete"));
            fail();
        } catch (Protocol.Failure busy) { assertEquals("VIRTUAL_CAMERA_BUSY", busy.code); assertEquals(1, busy.getSuppressed().length); }
    }
}
