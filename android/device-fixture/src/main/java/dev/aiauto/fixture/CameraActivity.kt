package dev.aiauto.fixture

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.widget.LinearLayout
import android.widget.TextView

/** 功能用途：提供仅测试应用的相机页，正常申请权限、展示后置预览并输出四象限采样与错误。 */
@SuppressLint("SetTextI18n")
class CameraActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var state: TextView
    private lateinit var deviceState: TextView
    private lateinit var preview: TextureView
    private var worker: HandlerThread? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    @Volatile private var active = false
    private var opening = false
    private var sampledAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = TextView(this).apply { id = R.id.camera_state; text = "CAMERA:WAITING"; setTextColor(Color.BLACK) }
        deviceState = TextView(this).apply { id = R.id.camera_device; text = "CAMERA_DEVICE:unopened"; setTextColor(Color.BLACK) }
        preview = TextureView(this).apply { id = R.id.camera_preview; surfaceTextureListener = this@CameraActivity }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 251, 252))
            addView(state); addView(deviceState)
            addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onResume() {
        super.onResume(); active = true
        worker = HandlerThread("fixture-camera").also { it.start() }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            state.text = "CAMERA:ERROR:PERMISSION_REQUIRED"
            // 权限由用户在系统框中决定，fixture 不使用 shell、appops 或其它旁路。
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 71)
        } else open()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) open()
            else state.text = "CAMERA:ERROR:PERMISSION_DENIED"
        }
    }

    private fun fail(reason: String) { runOnUiThread { if (active) state.text = "CAMERA:ERROR:$reason" } }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (!active || opening || camera != null || !preview.isAvailable || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val handler = worker?.looper?.let(::Handler) ?: return
        opening = true
        try {
            val manager = getSystemService(CameraManager::class.java)
            val ids = manager.cameraIdList
            val id = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            if (id == null) { opening = false; fail("NO_BACK_CAMERA"); return }
            val orientation = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION)
            deviceState.text = "CAMERA_DEVICE:id=$id,facing=back,orientation=$orientation,visible=${ids.joinToString("|")},display=${display?.displayId}"
            state.text = "CAMERA:OPENING"
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) {
                    if (!active) { opened.close(); return }
                    camera = opened
                    configure(opened, handler)
                }
                override fun onDisconnected(opened: CameraDevice) { opened.close(); fail("DISCONNECTED") }
                override fun onError(opened: CameraDevice, error: Int) { opened.close(); fail("OPEN_$error") }
            }, handler)
        } catch (e: Exception) { opening = false; fail("OPEN_${e.javaClass.simpleName}:${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun configure(opened: CameraDevice, handler: Handler) {
        try {
            val texture = preview.surfaceTexture ?: error("preview unavailable")
            texture.setDefaultBufferSize(640, 480)
            val surface = Surface(texture).also { previewSurface = it }
            val sampler = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3).also { reader = it }
            sampler.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    if (active && SystemClock.elapsedRealtime() - sampledAt >= 500) {
                        sampledAt = SystemClock.elapsedRealtime()
                        try {
                            val text = sample(image)
                            runOnUiThread { if (active) state.text = text }
                        } catch (e: Exception) { fail("SAMPLE_${e.javaClass.simpleName}") }
                    }
                }
            }, handler)
            opened.createCaptureSession(listOf(surface, sampler.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (!active) { configured.close(); return }
                    session = configured
                    try {
                        val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface); addTarget(sampler.surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(30, 30))
                        }
                        configured.setRepeatingRequest(request.build(), null, handler)
                    } catch (e: Exception) { fail("REQUEST_${e.javaClass.simpleName}:${e.message}") }
                }
                override fun onConfigureFailed(configured: CameraCaptureSession) { fail("CONFIGURE_FAILED") }
            }, handler)
        } catch (e: Exception) { fail("CONFIGURE_${e.javaClass.simpleName}:${e.message}") }
    }

    private fun sample(image: Image): String {
        val crop = image.cropRect
        fun point(x: Int, y: Int): String {
            fun plane(index: Int, divisor: Int): Int = image.planes[index].let {
                CameraSample.channel(it.buffer, it.rowStride, it.pixelStride, x / divisor, y / divisor)
            }
            return CameraSample.color(plane(0, 1), plane(1, 2), plane(2, 2))
        }
        val left = crop.left + crop.width() / 4; val right = crop.left + crop.width() * 3 / 4
        val top = crop.top + crop.height() / 4; val bottom = crop.top + crop.height() * 3 / 4
        return "CAMERA:TL=${point(left, top)},TR=${point(right, top)},BL=${point(left, bottom)},BR=${point(right, bottom)}"
    }

    override fun onPause() {
        active = false; opening = false
        session?.close(); session = null; camera?.close(); camera = null
        reader?.close(); reader = null; previewSurface?.release(); previewSurface = null
        worker?.quitSafely(); worker = null
        super.onPause()
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = open()
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
