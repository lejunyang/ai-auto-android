package dev.aiauto.fixture

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 功能用途：提供仅测试应用的相机页，正常申请权限、按4:3展示预览并有界恢复断流，保留可机器断言的采样文本。 */
@SuppressLint("SetTextI18n")
class CameraActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var state: TextView
    private lateinit var deviceState: TextView
    private lateinit var preview: TextureView
    private val main = Handler(Looper.getMainLooper())
    private val policy = CameraSessionPolicy()
    private var pendingRetry: Runnable? = null
    private var worker: HandlerThread? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    @Volatile private var active = false
    private var sampledAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        state = TextView(this).apply { id = R.id.camera_state; text = "CAMERA:WAITING"; setTextColor(Color.BLACK) }
        deviceState = TextView(this).apply { id = R.id.camera_device; text = "CAMERA_DEVICE:unopened"; setTextColor(Color.BLACK) }
        preview = TextureView(this).apply { id = R.id.camera_preview; surfaceTextureListener = this@CameraActivity }
        val area = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val (w, h) = CameraPreviewSize.fit(right - left, bottom - top)
                if (preview.layoutParams.width != w || preview.layoutParams.height != h) {
                    preview.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 251, 252))
            // API35+边到边布局仍避开系统栏；此测试页不显示ActionBar，文本不能被遮盖。
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            addView(state); addView(deviceState)
            addView(area, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            requestApplyInsets()
        })
    }

    override fun onResume() {
        super.onResume()
        policy.resume(); active = true
        worker = HandlerThread("fixture-camera").also { it.start() }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            state.text = "CAMERA:ERROR:PERMISSION_REQUIRED"
            // 权限由用户决定；恢复逻辑不请求默认设备权限，不使用授权旁路。
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 71)
        } else open()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 用户明确再次启动顶部实例时可开启新预算；成功回调不会重置自动重试预算。
        if (active && policy.phase == CameraSessionPolicy.Phase.EXHAUSTED) {
            cancelRetry(); closeCapture(); policy.resume(); open()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) open()
            else state.text = "CAMERA:ERROR:PERMISSION_DENIED"
        }
    }

    private fun cancelRetry() { pendingRetry?.let(main::removeCallbacks); pendingRetry = null }

    private fun failed(token: Int, reason: String) {
        if (!active || !policy.isCurrent(token)) return
        val retry = policy.failed(token, reason)
        cancelRetry(); closeCapture()
        if (retry == null) {
            state.text = "CAMERA:ERROR:${policy.lastError}:RETRIES=${policy.retries}/3:EXHAUSTED"
            return
        }
        state.text = "CAMERA:RETRY:${retry.attempt}/3:DELAY_MS=${retry.delayMs}:LAST_ERROR=${policy.lastError}"
        val action = Runnable {
            pendingRetry = null
            if (active && policy.retryReady(retry.generation)) open()
        }
        pendingRetry = action; main.postDelayed(action, retry.delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (!active || !preview.isAvailable || policy.phase != CameraSessionPolicy.Phase.READY) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            policy.stop(); state.text = "CAMERA:ERROR:PERMISSION_DENIED:RETRIES=${policy.retries}/3"; return
        }
        val handler = worker?.looper?.let(::Handler) ?: return
        val token = policy.beginOpen() ?: return
        sampledAt = 0
        try {
            val manager = getSystemService(CameraManager::class.java)
            val ids = manager.cameraIdList
            val id = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            if (id == null) { failed(token, "NO_BACK_CAMERA"); return }
            val orientation = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION)
            deviceState.text = "CAMERA_DEVICE:id=$id,facing=back,orientation=$orientation,visible=${ids.joinToString("|")},display=${display?.displayId},retries=${policy.retries}/3"
            state.text = "CAMERA:OPENING:RETRIES=${policy.retries}/3"
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) = runOnUiThread {
                    if (!active || !policy.opened(token)) { opened.close(); return@runOnUiThread }
                    camera = opened; configure(opened, handler, token)
                }
                override fun onDisconnected(opened: CameraDevice) = runOnUiThread {
                    opened.close(); if (policy.isCurrent(token)) failed(token, "DISCONNECTED")
                }
                override fun onError(opened: CameraDevice, error: Int) = runOnUiThread {
                    opened.close(); if (policy.isCurrent(token)) failed(token, "OPEN_$error")
                }
            }, handler)
        } catch (e: Exception) { failed(token, "OPEN_${e.javaClass.simpleName}:${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun configure(opened: CameraDevice, handler: Handler, token: Int) {
        try {
            val texture = preview.surfaceTexture ?: error("preview unavailable")
            texture.setDefaultBufferSize(640, 480)
            val surface = Surface(texture).also { previewSurface = it }
            val sampler = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3).also { reader = it }
            sampler.setOnImageAvailableListener({ source ->
                try {
                    source.acquireLatestImage()?.use { image ->
                        if (active && policy.isCurrent(token) && SystemClock.elapsedRealtime() - sampledAt >= 500) {
                            sampledAt = SystemClock.elapsedRealtime()
                            val text = sample(image)
                            runOnUiThread { if (active && policy.isCurrent(token)) state.text = text }
                        }
                    }
                } catch (e: Exception) { runOnUiThread { failed(token, "SAMPLE_${e.javaClass.simpleName}") } }
            }, handler)
            opened.createCaptureSession(listOf(surface, sampler.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) = runOnUiThread {
                    if (!active || !policy.isCurrent(token)) { configured.close(); return@runOnUiThread }
                    session = configured
                    try {
                        val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface); addTarget(sampler.surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(30, 30))
                        }
                        configured.setRepeatingRequest(request.build(), null, handler)
                    } catch (e: Exception) { failed(token, "REQUEST_${e.javaClass.simpleName}:${e.message}") }
                }
                override fun onConfigureFailed(configured: CameraCaptureSession) = runOnUiThread {
                    configured.close(); failed(token, "CONFIGURE_FAILED")
                }
            }, handler)
        } catch (e: Exception) { failed(token, "CONFIGURE_${e.javaClass.simpleName}:${e.message}") }
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

    private fun closeCapture() {
        // 资源只在UI线程替换，关闭前代次已失效，迟到回调不得复活旧会话。
        session?.close(); session = null; camera?.close(); camera = null
        reader?.setOnImageAvailableListener(null, null); reader?.close(); reader = null
        previewSurface?.release(); previewSurface = null
    }

    override fun onPause() {
        active = false; policy.stop(); cancelRetry(); closeCapture()
        worker?.quitSafely(); worker = null
        super.onPause()
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = open()
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (active && policy.phase in setOf(CameraSessionPolicy.Phase.OPENING, CameraSessionPolicy.Phase.STREAMING)) {
            failed(policy.generation, "PREVIEW_DESTROYED")
        } else closeCapture()
        return true
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
