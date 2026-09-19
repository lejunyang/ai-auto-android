package dev.aiauto.android.ui.overlay

/**
 * 功能用途：受控悬浮窗控制器，负责权限检查、WindowManager 增删 View 与状态分发。
 *
 * 职责：
 * - 检查悬浮窗权限（[android.provider.Settings.canDrawOverlays]），未授权时提供跳转 Intent。
 * - 通过 WindowManager 以 TYPE_APPLICATION_OVERLAY 添加/移除 ComposeView。
 * - 持有会话状态与展开/收起状态，驱动 Compose 自动重组。
 * - destroy() 移除悬浮窗并释放资源，避免 WindowLeak。
 *
 * 使用约束：
 * - 所有方法必须在主线程调用（与 UI 操作一致）。
 * - 悬浮窗使用 FLAG_NOT_FOCUSABLE，不获取焦点、不拦截后台操作。
 * - 不承诺锁屏显示或永久后台；进程或 Activity 回收后悬浮窗随之移除。
 */

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import dev.aiauto.android.ui.theme.AiAutoAndroidTheme

class OverlayController(private val context: Context) {

    // 使用 applicationContext 避免持有 Activity 引用导致泄漏。
    private val appContext: Context = context.applicationContext

    // 悬浮窗专用 LifecycleOwner：applicationContext 无 Activity，需手动提供
    // LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner，否则
    // ComposeView attach 到 WindowManager 时抛 ViewTreeLifecycleOwner not found。
    private val overlayLifecycle = OverlayLifecycleOwner().apply {
        performCreate()
        handleLifecycleEvent(Lifecycle.Event.ON_START)
        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private val windowManager: WindowManager? =
        appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    // 当前挂载的悬浮窗 View；null 表示未显示。
    private var composeView: ComposeView? = null

    // 悬浮窗是否已通过 WindowManager 添加。
    private var isShowing: Boolean = false

    // Compose 状态：会话状态与展开/收起，变化时触发重组。
    private val sessionState = mutableStateOf<OverlaySessionState>(OverlaySessionState.Idle)
    private val expanded = mutableStateOf(false)

    // 外层会话控制方提供的操作回调；内部展开/收起会先更新自身状态再转发。
    private var externalActions: OverlayActions? = null

    /**
     * 权限未授权时的回调：show() 发现无悬浮窗权限时触发，供调用方拉起系统设置。
     * 设为 null 时静默返回，不崩溃。
     */
    var onPermissionRequired: (() -> Unit)? = null

    /** 检查当前是否拥有悬浮窗权限。 */
    fun hasPermission(): Boolean = Settings.canDrawOverlays(appContext)

    /**
     * 跳转系统「悬浮窗权限」设置页的 Intent。
     * 调用方负责 startActivity 并在 onActivityResult 后重新 show()。
     */
    fun createPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${appContext.packageName}"),
    )

    /** 注入外部操作回调（暂停/继续/停止等）。传 null 清空。 */
    fun setActions(actions: OverlayActions?) {
        externalActions = actions
    }

    /**
     * 更新会话状态，悬浮窗内容自动重组。
     * 未显示时调用仅缓存状态，下次 show() 时按最新状态渲染。
     */
    fun updateState(state: OverlaySessionState) {
        sessionState.value = state
    }

    /** 公开：展开悬浮窗卡片，供外部（debug 演练场、前台服务）直接控制展开形态。 */
    fun expand() {
        expanded.value = true
        updateWindowSize()
    }

    /** 公开：收起为边缘小圆/药丸，悬浮窗仍保持挂载；区别于 hide() 的彻底移除。 */
    fun collapse() {
        expanded.value = false
        updateWindowSize()
    }

    /**
     * 展开/收起切换时动态更新窗口尺寸：
     * - 收起态：48dp×48dp 小窗，只挡圆那一小块，不拦截后台触摸。
     * - 展开态：280dp×200dp 卡片窗，贴合卡片内容。
     * 用 updateViewLayout 而非重建窗口，避免闪烁。
     */
    private fun updateWindowSize() {
        val view = composeView ?: return
        val wm = windowManager ?: return
        val params = buildLayoutParams()
        runCatching { wm.updateViewLayout(view, params) }
            .onSuccess { android.util.Log.i("OverlayCtrl", "updateViewLayout to expanded=${expanded.value}") }
            .onFailure { e -> android.util.Log.e("OverlayCtrl", "updateViewLayout failed: ${e.message}", e) }
    }

    /**
     * 显示悬浮窗。
     * 无权限时触发 [onPermissionRequired] 并直接返回，不抛异常。
     * 已显示时重复调用无副作用。
     */
    fun show() {
        val permission = hasPermission()
        android.util.Log.i("OverlayCtrl", "show() called, hasPermission=$permission, isShowing=$isShowing")
        if (!permission) {
            // 权限拒绝：明确回调，不崩溃、不强行 addView。
            android.util.Log.w("OverlayCtrl", "show() no overlay permission")
            onPermissionRequired?.invoke()
            return
        }
        if (isShowing) {
            android.util.Log.d("OverlayCtrl", "show() already showing, skip")
            return
        }

        val wm = windowManager ?: return
        val view = ComposeView(appContext).apply {
            // 悬浮窗 context 为 applicationContext，无 Activity 关联的 LifecycleOwner，
            // 必须手动设置 ViewTree owner，否则 ComposeView attach 时崩溃
            // (IllegalStateException: ViewTreeLifecycleOwner not found)。
            // 用反射调用 androidx.lifecycle.ViewTreeLifecycleOwner / ViewTreeViewModelStoreOwner
            // 的静态 set 方法——这些类在运行时 classpath 中但不在编译 classpath 中
            // (lifecycle KMP 架构下 api 制品未传递)，反射是最稳的接入方式。
            attachOverlayViewTreeOwners(overlayLifecycle)
            android.util.Log.i("OverlayCtrl", "setContent called, state=${sessionState.value}, expanded=${expanded.value}")
            setContent {
                android.util.Log.i("OverlayCtrl", "Compose composition started")
                AiAutoAndroidTheme {
                    OverlayWindowContent(
                        state = sessionState.value,
                        expanded = expanded.value,
                        actions = wiredActions(),
                    )
                }
            }
        }

        val params = buildLayoutParams()
        android.util.Log.i("OverlayCtrl", "about to addView, params=$params")
        runCatching { wm.addView(view, params) }
            .onSuccess {
                composeView = view
                isShowing = true
                android.util.Log.i("OverlayCtrl", "addView success, overlay visible")
            }
            .onFailure { e ->
                // addView 失败（如权限刚被回收）不崩溃，保持未显示状态。
                android.util.Log.e("OverlayCtrl", "addView failed: ${e.javaClass.simpleName}: ${e.message}", e)
                composeView = null
                isShowing = false
            }
    }

    /** 隐藏悬浮窗：从 WindowManager 移除 View。未显示时无副作用。 */
    fun hide() {
        val view = composeView ?: return
        val wm = windowManager ?: return
        runCatching { wm.removeView(view) }
        composeView = null
        isShowing = false
    }

    /** 切换显示/隐藏。 */
    fun toggle() {
        if (isShowing) hide() else show()
    }

    /**
     * 销毁控制器：移除悬浮窗、清空回调、销毁 Lifecycle，防止 WindowLeak 与 Activity 泄漏。
     * 调用后不应再使用本实例。
     */
    fun destroy() {
        hide()
        overlayLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        externalActions = null
        onPermissionRequired = null
    }

    /** 当前是否正在显示。 */
    fun isShowing(): Boolean = isShowing

    /**
     * 构造悬浮窗布局参数。
     * 类型为 TYPE_APPLICATION_OVERLAY（Android 8.0+ 标准悬浮窗类型）。
     * 标志位：
     * - FLAG_NOT_FOCUSABLE：不获取输入焦点，不拦截后台按键。
     * - FLAG_LAYOUT_NO_LIMITS：允许绘制到屏幕边缘外，避免圆角裁切。
     * 初始位置：右上角（Gravity.END|TOP），留出边距避免遮挡状态栏。
     */
    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val density = appContext.resources.displayMetrics.density
        // dp 转像素：WindowManager 布局参数的 x/y 单位为 px。
        val marginPx = (OVERLAY_MARGIN_DP * density).toInt()
        val topOffsetPx = (OVERLAY_TOP_OFFSET_DP * density).toInt()
        // 窗口尺寸严格贴合可见内容：
        // - 收起态：48dp×48dp 小圆，只挡右上角一小块，不拦截后台触摸。
        // - 展开态：280dp×200dp 卡片窗，贴合 ExpandedCard 内容。
        // 两态内容尺寸均确定，避免 WRAP_CONTENT 首次 measure 量出 0 的问题。
        val (w, h) = if (expanded.value) {
            EXPANDED_WIDTH_DP to EXPANDED_HEIGHT_DP
        } else {
            COLLAPSED_SIZE_DP to COLLAPSED_SIZE_DP
        }
        return WindowManager.LayoutParams(
            (w * density).toInt(),
            (h * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = marginPx
            y = topOffsetPx
        }
    }

    /**
     * 组装渲染时使用的回调：展开/收起先翻转内部状态，再转发给外部会话回调。
     * 这样即使外部未设置 actions，UI 自身的展开/收起仍可用。
     */
    private fun wiredActions(): OverlayActions = object : OverlayActions {
        override fun onPause() {
            externalActions?.onPause()
        }

        override fun onResume() {
            externalActions?.onResume()
        }

        override fun onStop() {
            externalActions?.onStop()
        }

        override fun onExpand() {
            expanded.value = true
            // 同步物理窗口尺寸，否则窗口仍为收起小窗、展开内容被裁切。
            updateWindowSize()
            externalActions?.onExpand()
        }

        override fun onCollapse() {
            expanded.value = false
            // 同步物理窗口尺寸，否则残留展开大窗会拦截下方应用触摸。
            updateWindowSize()
            externalActions?.onCollapse()
        }
    }

    companion object {
        // 距屏幕右缘边距（dp）。
        private const val OVERLAY_MARGIN_DP = 16
        // 距屏幕顶部初始偏移（dp），避开状态栏。
        private const val OVERLAY_TOP_OFFSET_DP = 96
        // 收起态窗口尺寸（dp）：48dp 小圆，只挡右上角一小块。
        private const val COLLAPSED_SIZE_DP = 48
        // 展开态窗口尺寸（dp）：280dp 宽 × 200dp 高，贴合 ExpandedCard。
        private const val EXPANDED_WIDTH_DP = 280
        private const val EXPANDED_HEIGHT_DP = 200
    }
}
