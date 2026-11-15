package dev.aiauto.displayd;

import static org.junit.Assert.*;
import org.junit.Test;

/** 测试用途：验证服务死亡和绘制失败的保守诊断、未知监视状态及终态不被迟到成功覆盖，不自动恢复。 */
public class CameraAvailabilityTest {
    @Test public void monitoredRegistrationDoesNotMeanFrameReadback() {
        var state = new CameraAvailability();
        assertEquals("unknown", state.describe().get("availability"));
        state.registered(true);
        assertEquals("available", state.describe().get("availability"));
        assertNull(state.describe().get("availabilityReason"));
    }
    @Test public void unmonitoredRegistrationRemainsUnknown() {
        var state = new CameraAvailability(); state.registered(false);
        assertEquals("unknown", state.describe().get("availability"));
        assertEquals("SERVICE_MONITOR_UNAVAILABLE", state.describe().get("availabilityReason"));
    }
    @Test public void deathIsTerminalAndMoreSpecificThanRenderFailure() {
        var state = new CameraAvailability(); state.renderFailed("eglSwapBuffers failed EGL=12301");
        assertEquals("unavailable", state.describe().get("availability"));
        state.serviceDied(); state.registered(true); state.renderFailed("another failure");
        assertEquals("unavailable", state.describe().get("availability"));
        assertEquals("VIRTUAL_CAMERA_SERVICE_DIED", state.describe().get("availabilityReason"));
    }
    @Test public void renderFailureIsBoundedAndSurvivesStreamRemoval() {
        var state = new CameraAvailability(); state.registered(true);
        state.renderFailed("error\n" + "x".repeat(500));
        String reason = (String) state.describe().get("availabilityReason");
        assertEquals("unavailable", state.describe().get("availability"));
        assertTrue(reason.startsWith("STREAM_RENDER_FAILED:")); assertFalse(reason.contains("\n"));
        assertTrue(reason.length() <= 280);
    }
}
