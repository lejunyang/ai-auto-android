package dev.aiauto.displayd;

import static org.junit.Assert.*;
import static dev.aiauto.displayd.FrameworkDisplayFlags.VirtualDisplayRequest.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** 测试用途：验证摄像头显式启用、图片预算、临时路径及等比留边，防止越界文件读取与隐式开启。 */
public class CameraPolicyTest {
    @Test public void displayRequestPreservesDestroyAndFocusPolicies() throws Exception {
        for (String focus : new String[]{"isolated", "steal"}) {
            int ordinary = Policy.flags(36, focus);
            int camera = CameraPolicy.displayFlags(ordinary);
            assertEquals(DESTROY_CONTENT_ON_REMOVAL, camera & DESTROY_CONTENT_ON_REMOVAL);
            assertEquals(0, camera & SECURE);
            assertEquals(OWN_DISPLAY_GROUP, camera & OWN_DISPLAY_GROUP);
            assertEquals(ordinary, camera);
        }
        assertEquals(STEAL_TOP_FOCUS_DISABLED,
                CameraPolicy.displayFlags(Policy.flags(36, "isolated")) ^ CameraPolicy.displayFlags(Policy.flags(36, "steal")));
        assertEquals(0, CameraPolicy.displayFlags(SECURE) & SECURE);
        assertEquals(DESTROY_CONTENT_ON_REMOVAL, CameraPolicy.displayFlags(0) & DESTROY_CONTENT_ON_REMOVAL);
    }
    @Test public void requestAndResultNamespacesRemainDistinct() {
        assertEquals(0x100, DESTROY_CONTENT_ON_REMOVAL);
        assertEquals(0x800, OWN_DISPLAY_GROUP);
        assertEquals(0x100, FrameworkDisplayFlags.DisplayResult.OWN_DISPLAY_GROUP);
        assertNotEquals(OWN_DISPLAY_GROUP, FrameworkDisplayFlags.DisplayResult.OWN_DISPLAY_GROUP);
    }
    private void failure(String code, RunnableFailure action) throws Exception {
        try { action.run(); fail(code); } catch (Protocol.Failure e) { assertEquals(code, e.code); }
    }
    interface RunnableFailure { void run() throws Exception; }
    @Test public void enabledAndAPI() throws Exception {
        failure("VIRTUAL_CAMERA_NOT_ENABLED", () -> CameraPolicy.requireAvailable(false, 36));
        failure("VIRTUAL_CAMERA_UNAVAILABLE", () -> CameraPolicy.requireAvailable(true, 34));
        CameraPolicy.requireAvailable(true, 35);
    }
    @Test public void imageBudgetsAndMime() throws Exception {
        CameraPolicy.image(8 * 1024 * 1024, 4096, 4096, "image/png");
        CameraPolicy.image(1, 1, 1, "image/jpeg");
        failure("CAMERA_IMAGE_INVALID", () -> CameraPolicy.image(8388609, 1, 1, "image/png"));
        failure("CAMERA_IMAGE_INVALID", () -> CameraPolicy.image(0, 1, 1, "image/png"));
        failure("CAMERA_IMAGE_INVALID", () -> CameraPolicy.image(1, 4097, 1, "image/png"));
        failure("CAMERA_IMAGE_INVALID", () -> CameraPolicy.image(1, 1, 0, "image/png"));
        failure("CAMERA_IMAGE_INVALID", () -> CameraPolicy.image(1, 1, 1, "image/webp"));
    }
    @Test public void onlyUUIDTransferPath() throws Exception {
        CameraPolicy.path("/data/local/tmp/aactl-camera-12345678-1234-4234-8234-123456789abc.img");
        for (String p : new String[]{"/etc/passwd", "/data/local/tmp/aactl-camera-../x.img", "/data/local/tmp/aactl-camera-123.img", "/data/local/tmp/aactl-camera-12345678-1234-4234-8234-123456789abc.img/x"})
            failure("CAMERA_IMAGE_INVALID", () -> CameraPolicy.path(p));
    }
    @Test public void containWithoutStretchOrCrop() {
        assertArrayEquals(new int[]{0, 60, 640, 420}, CameraPolicy.fit(1920, 1080, 640, 480));
        assertArrayEquals(new int[]{185, 0, 455, 480}, CameraPolicy.fit(720, 1280, 640, 480));
        assertArrayEquals(new int[]{0, 0, 640, 480}, CameraPolicy.fit(640, 480, 640, 480));
    }
    @Test public void onlyAdvertisedStreamDimensions() {
        assertTrue(CameraPolicy.acceptsStream(640, 480));
        assertTrue(CameraPolicy.acceptsStream(1280, 720));
        assertTrue(CameraPolicy.acceptsStream(1920, 1080));
        assertFalse(CameraPolicy.acceptsStream(4096, 4096));
        assertFalse(CameraPolicy.acceptsStream(0, 480));
    }
    @Test public void optionalCameraAndNewMethod() throws Exception {
        var r = Protocol.read(new ByteArrayInputStream(("{\"id\":1,\"method\":\"display.create\",\"width\":640,\"height\":480,\"dpi\":240,\"camera\":\"back\"}\n").getBytes(StandardCharsets.UTF_8)));
        assertEquals("back", r.camera()); assertEquals("isolated", r.focus());
        String p = "/data/local/tmp/aactl-camera-12345678-1234-4234-8234-123456789abc.img";
        r = Protocol.read(new ByteArrayInputStream(("{\"id\":1,\"method\":\"camera.setImage\",\"displayId\":2,\"path\":\"" + p + "\"}\n").getBytes(StandardCharsets.UTF_8)));
        assertEquals(p, r.path()); assertEquals(2, r.displayId());
        failure("INVALID_ARGUMENT", () -> Protocol.read(new ByteArrayInputStream("{\"id\":1,\"method\":\"display.create\",\"width\":640,\"height\":480,\"dpi\":240,\"camera\":\"front\"}\n".getBytes(StandardCharsets.UTF_8))));
    }
}
