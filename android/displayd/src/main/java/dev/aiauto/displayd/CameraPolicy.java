package dev.aiauto.displayd;

/** 功能用途：约束摄像头启用、图片文件预算与等比居中规则，纯逻辑不依赖设备框架。 */
final class CameraPolicy {
    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    static final String PATH_PATTERN = "/data/local/tmp/aactl-camera-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.img";
    static void requireAvailable(boolean enabled, int api) throws Protocol.Failure {
        if (!enabled) throw new Protocol.Failure("VIRTUAL_CAMERA_NOT_ENABLED",
                "Operator must stop and restart helper with aactl display start --device SERIAL --virtual-camera");
        if (api < 35) throw new Protocol.Failure("VIRTUAL_CAMERA_UNAVAILABLE", "Virtual camera requires API 35+");
    }
    static void path(String path) throws Protocol.Failure {
        if (path == null || !path.matches(PATH_PATTERN)) throw invalid("image path must match the tool UUID transfer path");
    }
    static void image(long bytes, int width, int height, String mime) throws Protocol.Failure {
        if (bytes < 1 || bytes > MAX_IMAGE_BYTES || width < 1 || height < 1 || width > 4096 || height > 4096
                || !("image/png".equals(mime) || "image/jpeg".equals(mime)))
            throw invalid("expected PNG/JPEG <= 8 MiB and decoded dimensions 1..4096");
    }
    static Protocol.Failure invalid(String reason) { return new Protocol.Failure("CAMERA_IMAGE_INVALID", reason); }
    static boolean acceptsStream(int width, int height) {
        return (width == 640 && height == 480) || (width == 1280 && height == 720) || (width == 1920 && height == 1080);
    }
    static int[] fit(int sourceW, int sourceH, int targetW, int targetH) {
        double scale = Math.min((double) targetW / sourceW, (double) targetH / sourceH);
        int width = Math.max(1, (int) Math.round(sourceW * scale));
        int height = Math.max(1, (int) Math.round(sourceH * scale));
        int left = (targetW - width) / 2, top = (targetH - height) / 2;
        return new int[]{left, top, left + width, top + height};
    }
}
