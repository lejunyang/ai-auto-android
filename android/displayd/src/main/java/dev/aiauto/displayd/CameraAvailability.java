package dev.aiauto.displayd;

import java.util.Map;

/** 功能用途：提供保守的虚拟相机失效诊断；只根据注册监视与实际失败标记状态，禁止自动恢复或把旧编号当活性证据。 */
final class CameraAvailability {
    private String availability = "unknown";
    private String reason = "NOT_REGISTERED";
    synchronized void registered(boolean monitored) {
        if (availability.equals("unavailable")) return;
        availability = monitored ? "available" : "unknown";
        reason = monitored ? null : "SERVICE_MONITOR_UNAVAILABLE";
    }
    synchronized void serviceDied() {
        availability = "unavailable"; reason = "VIRTUAL_CAMERA_SERVICE_DIED";
    }
    synchronized void renderFailed(String error) {
        if (availability.equals("unavailable")) return;
        String detail = String.valueOf(error).replaceAll("[\\r\\n]", " ");
        availability = "unavailable";
        reason = "STREAM_RENDER_FAILED: " + detail.substring(0, Math.min(256, detail.length()));
    }
    synchronized Map<String, Object> describe() {
        return Protocol.fields("availability", availability, "availabilityReason", reason);
    }
}
