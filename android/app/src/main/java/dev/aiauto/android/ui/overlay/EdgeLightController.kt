package dev.aiauto.android.ui.overlay

/**
 * 功能用途：会话运行时的屏幕边缘呼吸光效（edge-light），对齐原型 .edge-light。
 *
 * 边界与安全：
 * - 独立全屏 TYPE_APPLICATION_OVERLAY 窗口，FLAG_NOT_TOUCHABLE 完全触摸穿透，不挡任何操作。
 * - 实现 ScreenshotOverlayShutter：AI 无障碍截图期间窗口临时 INVISIBLE，光效不进入 AI 感知截图，
 *   截图后恢复；不使用会导致整屏截图失败/变黑的 FLAG_SECURE。
 * - Compose 根 clearAndSetSemantics 清空语义，且 ComposeView importantForAccessibility=NO，
 *   不进入无障碍语义树，避免被 AI 误识别为被操作 App 的元素。
 */

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.aiauto.android.accessibility.ScreenshotOverlayShutter
import dev.aiauto.android.accessibility.ScreenshotOverlayShutterHolder
import dev.aiauto.android.ui.theme.AiAutoAndroidTheme

/** edge-light 显示模式。 */
enum class EdgeLightMode {
    /** 不显示。 */
    OFF,

    /** 助理工作中（观察/规划/执行/校验）：蓝色呼吸。 */
    ACTIVE,

    /** 等待用户确认：金色静态。 */
    WAITING,

    /** 已暂停：蓝色静态、动画停止。 */
    PAUSED,
}

/**
 * edge-light 窗口控制器；所有方法应在主线程调用。
 */
class EdgeLightController(context: Context) : ScreenshotOverlayShutter {

    private val appContext: Context = context.applicationContext
    private val windowManager: WindowManager? =
        appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    // 截图遮挡计数：支持并发截图嵌套，全部恢复（归零）后才重新显示光效。
    private var suppressCount = 0

    private val lifecycle = OverlayLifecycleOwner().apply {
        performCreate()
        handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
    }

    private var composeView: ComposeView? = null
    private var showing = false
    private val modeState = androidx.compose.runtime.mutableStateOf(EdgeLightMode.OFF)

    /** 更新模式；OFF 移除窗口，其余确保窗口挂载，内容随状态重绘。 */
    fun setMode(mode: EdgeLightMode) {
        if (modeState.value == mode) return
        modeState.value = mode
        if (mode == EdgeLightMode.OFF) removeWindow() else ensureWindow()
    }

    private fun ensureWindow() {
        val wm = windowManager ?: return
        if (showing) return
        val view = ComposeView(appContext).apply {
            attachOverlayViewTreeOwners(lifecycle)
            // 整个 View 不参与无障碍，配合 Composable 内 clearAndSetSemantics 双保险。
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // 创建时若正处于截图遮挡期，窗口初始即不可见。
            if (suppressCount > 0) visibility = View.INVISIBLE
            setContent {
                AiAutoAndroidTheme {
                    EdgeLight(
                        mode = modeState.value,
                        modifier = Modifier
                            .fillMaxSize()
                            .clearAndSetSemantics { },
                    )
                }
            }
        }
        val ok = runCatching { wm.addView(view, buildLayoutParams()) }.isSuccess
        if (ok) {
            composeView = view
            showing = true
            // 注册为当前截图遮挡 shutter，随窗口移除而注销。
            ScreenshotOverlayShutterHolder.register(this)
        }
    }

    private fun removeWindow() {
        val view = composeView ?: return
        val wm = windowManager ?: return
        runCatching { wm.removeView(view) }
        composeView = null
        showing = false
        // 窗口移除，注销 shutter，避免截图流程再操作已销毁窗口。
        ScreenshotOverlayShutterHolder.unregister(this)
    }

    /** 销毁：移除窗口并销毁 lifecycle。 */
    fun destroy() {
        removeWindow()
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
    }

    /** 截图遮挡：计数加一，窗口临时不可见，使光效不进入 AI 感知截图。 */
    override fun suppressForCapture() {
        suppressCount++
        composeView?.visibility = View.INVISIBLE
    }

    /** 截图结束：计数减一，全部截图结束（归零）后恢复窗口可见。 */
    override fun restoreAfterCapture() {
        if (suppressCount > 0) suppressCount--
        if (suppressCount == 0) composeView?.visibility = View.VISIBLE
    }

    /**
     * 全屏、触摸穿透、安全不可截屏的布局参数。
     */
    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不获取焦点 + 完全不接收触摸（穿透）+ 铺到屏幕边缘。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
}

/**
 * edge-light 绘制：四条内发光渐变带 + 内描边；ACTIVE 随 2.2s 呼吸，其余静态。
 */
@Composable
private fun EdgeLight(mode: EdgeLightMode, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "edge")
    // 半周期 1.1s + Reverse，完整呼吸周期 2.2s。
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "edgeBreathe",
    )
    // 静态/暂停态固定进度 0（常态蓝）。
    val progress = if (mode == EdgeLightMode.ACTIVE) breathe else 0f

    Canvas(modifier) {
        val frameColor: Color
        val glowColor: Color
        val glowWidthDp: Float
        if (mode == EdgeLightMode.WAITING) {
            // 金色待确认：静态。
            frameColor = Color(0xFFD6A75D)
            glowColor = Color(0x60E2B478)
            glowWidthDp = 22f
        } else {
            // 蓝色：在常态与呼吸高亮两组间插值；发光宽度 25dp→35dp。
            frameColor = lerp(Color(0xFF5796FA), Color(0xFF90B7FF), progress)
            glowColor = lerp(Color(0x6583ABFA), Color(0x90719AFA), progress)
            glowWidthDp = 25f + 10f * progress
        }
        val glowWidth = glowWidthDp * density
        val w = size.width
        val h = size.height

        // 左内发光。
        drawRect(
            brush = Brush.horizontalGradient(listOf(glowColor, Color.Transparent), 0f, glowWidth),
            topLeft = Offset(0f, 0f),
            size = Size(glowWidth, h),
        )
        // 右内发光。
        drawRect(
            brush = Brush.horizontalGradient(listOf(Color.Transparent, glowColor), w - glowWidth, w),
            topLeft = Offset(w - glowWidth, 0f),
            size = Size(glowWidth, h),
        )
        // 上内发光。
        drawRect(
            brush = Brush.verticalGradient(listOf(glowColor, Color.Transparent), 0f, glowWidth),
            topLeft = Offset(0f, 0f),
            size = Size(w, glowWidth),
        )
        // 下内发光。
        drawRect(
            brush = Brush.verticalGradient(listOf(Color.Transparent, glowColor), h - glowWidth, h),
            topLeft = Offset(0f, h - glowWidth),
            size = Size(w, glowWidth),
        )

        // 内描边：inset 半个线宽，保证 3dp 线完全在屏幕内。
        val strokeWidth = 3f * density
        val inset = strokeWidth / 2f
        drawRect(
            color = frameColor,
            topLeft = Offset(inset, inset),
            size = Size(w - strokeWidth, h - strokeWidth),
            style = Stroke(width = strokeWidth),
        )
    }
}
