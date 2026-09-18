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
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
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

    /**
     * 显示悬浮窗。
     * 无权限时触发 [onPermissionRequired] 并直接返回，不抛异常。
     * 已显示时重复调用无副作用。
     */
    fun show() {
        if (!hasPermission()) {
            // 权限拒绝：明确回调，不崩溃、不强行 addView。
            onPermissionRequired?.invoke()
            return
        }
        if (isShowing) return

        val wm = windowManager ?: return
        val view = ComposeView(appContext).apply {
            // 悬浮窗 context 为 applicationContext，无 Activity 关联的 LifecycleOwner，
            // 必须手动设置 ViewTree owner，否则 ComposeView attach 时崩溃
            // (IllegalStateException: ViewTreeLifecycleOwner not found)。
            // 用反射调用 androidx.lifecycle.ViewTreeLifecycleOwner / ViewTreeViewModelStoreOwner
            // 的静态 set 方法——这些类在运行时 classpath 中但不在编译 classpath 中
            // (lifecycle KMP 架构下 api 制品未传递)，反射是最稳的接入方式。
            setViewTreeOwners(this, overlayLifecycle)
            setContent {
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
        runCatching { wm.addView(view, params) }
            .onSuccess {
                composeView = view
                isShowing = true
            }
            .onFailure {
                // addView 失败（如权限刚被回收）不崩溃，保持未显示状态。
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
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            // 距屏幕右缘与顶部的初始边距，避开状态栏。
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
            externalActions?.onExpand()
        }

        override fun onCollapse() {
            expanded.value = false
            externalActions?.onCollapse()
        }
    }

    /**
     * 通过反射为 View 设置 ViewTreeLifecycleOwner 和 ViewTreeViewModelStoreOwner。
     *
     * 为什么用反射：androidx.lifecycle.ViewTreeLifecycleOwner / ViewTreeViewModelStoreOwner
     * 在 lifecycle KMP 架构下位于 api 制品中，编译 classpath 可能不包含，
     * 但运行时 classpath 一定有（lifecycle-runtime 传递依赖）。反射调用其静态
     * set(View, Owner) 方法是最稳的接入方式，不依赖具体 Maven 坐标或版本。
     *
     * 反射失败时静默降级：ComposeView attach 时会抛 IllegalStateException，
     * 但至少不会因为类找不到而编译失败。
     */
    private fun setViewTreeOwners(view: View, owner: OverlayLifecycleOwner) {
        try {
            val lifecycleOwnerClass = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            val setLifecycleMethod = lifecycleOwnerClass.getMethod("set", View::class.java, LifecycleOwner::class.java)
            setLifecycleMethod.invoke(null, view, owner)
        } catch (_: Exception) {
            // 反射失败静默降级，运行时 ComposeView attach 会给出明确崩溃信息。
        }
        try {
            val viewModelStoreClass = Class.forName("androidx.lifecycle.ViewTreeViewModelStoreOwner")
            val setViewModelMethod = viewModelStoreClass.getMethod("set", View::class.java, ViewModelStoreOwner::class.java)
            setViewModelMethod.invoke(null, view, owner)
        } catch (_: Exception) {
            // 同上，静默降级。
        }
        try {
            // ComposeView attach 时还需要 ViewTreeSavedStateRegistryOwner，
            // 否则抛 "Composed into the View which doesn't propagate ViewTreeSavedStateRegistryOwner!"。
            val savedStateClass = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
            val setSavedStateMethod = savedStateClass.getMethod("set", View::class.java, SavedStateRegistryOwner::class.java)
            setSavedStateMethod.invoke(null, view, owner)
        } catch (_: Exception) {
            // 同上，静默降级。
        }
    }

    companion object {
        // 距屏幕右缘边距（dp），右上角布局在 WRAP_CONTENT 下观感一致。
        private const val OVERLAY_MARGIN_DP = 16
        // 距屏幕顶部初始偏移（dp），避开状态栏。
        private const val OVERLAY_TOP_OFFSET_DP = 96
    }
}

/**
 * 悬浮窗专用 LifecycleOwner。
 *
 * 为什么需要：OverlayController 用 applicationContext 创建 ComposeView，
 * applicationContext 不关联任何 Activity，因此 ViewTreeLifecycleOwner /
 * ViewTreeViewModelStoreOwner / SavedStateRegistryOwner 均为空。ComposeView
 * attach 到 WindowManager 时 createLifecycleAwareWindowRecomposer 会抛
 * IllegalStateException: ViewTreeLifecycleOwner not found。
 *
 * 本类提供一个跟随 OverlayController 生命周期的最小实现：
 * - 创建时 performCreate + ON_START + ON_RESUME（悬浮窗始终在前台可见）
 * - destroy() 时 ON_DESTROY 并清理 ViewModelStore
 * - 不保存状态（悬浮窗为临时 UI，进程重启后重新创建）
 */
private class OverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() =
        savedStateController.savedStateRegistry

    /** 执行创建流程：attach savedState + restore null + 标记 CREATED。 */
    fun performCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** 转发 Lifecycle 事件（ON_START / ON_RESUME / ON_DESTROY）。 */
    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
        if (event == Lifecycle.Event.ON_DESTROY) {
            store.clear()
        }
    }
}
