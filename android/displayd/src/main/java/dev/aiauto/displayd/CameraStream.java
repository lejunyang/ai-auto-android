package dev.aiauto.displayd;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.function.Consumer;

/** 功能用途：每个活动流使用独立 EGL 位图渲染线程，等比留黑边且最高约 29.4fps；无流不绘制。 */
final class CameraStream implements AutoCloseable {
    interface ImageSource { void use(Consumer<Bitmap> consumer); }
    private final Surface surface;
    private final int width, height;
    private final ImageSource source;
    private final Consumer<String> failure;
    private volatile boolean stopped;
    private volatile String error;
    private volatile long frames;
    private final Thread thread;
    private Bitmap uploaded;
    private int imageWidth, imageHeight;
    CameraStream(Surface surface, int width, int height, ImageSource source, Consumer<String> failure) {
        this.surface = surface; this.width = width; this.height = height; this.source = source;
        this.failure = failure;
        thread = new Thread(this::render, "displayd-camera-stream"); thread.setDaemon(true);
    }
    void start() { thread.start(); }
    Map<String, Object> describe(int id) { return Protocol.fields("streamId", id, "width", width, "height", height, "frames", frames, "error", error); }
    @Override public void close() { stopped = true; thread.interrupt(); }
    private void render() {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface window = EGL14.EGL_NO_SURFACE;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2]; check(EGL14.eglInitialize(display, version, 0, version, 1), "eglInitialize");
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            check(EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE}, 0, configs, 0, 1, count, 0) && count[0] > 0, "eglChooseConfig");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface, new int[]{EGL14.EGL_NONE}, 0);
            check(EGL14.eglMakeCurrent(display, window, window, context), "eglMakeCurrent");
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER,
                    "attribute vec2 p; attribute vec2 uv; varying vec2 t; void main(){gl_Position=vec4(p,0.,1.);t=uv;}"));
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float; varying vec2 t; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,t);}"));
            GLES20.glLinkProgram(program); int[] linked = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            check(linked[0] != 0, "GL program link"); GLES20.glUseProgram(program);
            int[] texture = new int[1]; GLES20.glGenTextures(1, texture, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            FloatBuffer positions = buffer(-1, -1, 1, -1, -1, 1, 1, 1), uv = buffer(0, 1, 1, 1, 0, 0, 1, 0);
            attribute(program, "p", positions); attribute(program, "uv", uv);
            while (!stopped && surface.isValid()) {
                long start = System.nanoTime();
                source.use(bitmap -> {
                    if (bitmap != uploaded) {
                        if (bitmap != null) {
                            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                            imageWidth = bitmap.getWidth(); imageHeight = bitmap.getHeight();
                        }
                        uploaded = bitmap;
                    }
                });
                GLES20.glViewport(0, 0, width, height);
                GLES20.glClearColor(0, 0, 0, 1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                if (uploaded != null) {
                    int[] dst = CameraPolicy.fit(imageWidth, imageHeight, width, height);
                    GLES20.glViewport(dst[0], height - dst[3], dst[2] - dst[0], dst[3] - dst[1]);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                }
                check(GLES20.glGetError() == GLES20.GL_NO_ERROR, "GL draw/upload");
                if (stopped) break;
                check(EGL14.eglSwapBuffers(display, window), "eglSwapBuffers"); frames++;
                long remaining = 34_000_000L - (System.nanoTime() - start);
                if (remaining > 0) Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch (Exception e) {
            error = e.getMessage();
            // 正常关闭中的表面失效不污染相机状态；真实渲染失败保留到关闭屏，不能随流移除丢失。
            if (!stopped) failure.accept(error);
            System.err.println("camera stream stopped: " + error);
        }
        finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglTerminate(display); EGL14.eglReleaseThread();
            }
            surface.release(); uploaded = null;
        }
    }
    private static void check(boolean valid, String operation) {
        if (!valid) throw new IllegalStateException(operation + " failed EGL=" + EGL14.eglGetError());
    }
    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] status = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        check(status[0] != 0, "shader compile"); return shader;
    }
    private static FloatBuffer buffer(float... values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0); return buffer;
    }
    private static void attribute(int program, String name, FloatBuffer data) {
        int location = GLES20.glGetAttribLocation(program, name); GLES20.glEnableVertexAttribArray(location);
        GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, data);
    }
}
