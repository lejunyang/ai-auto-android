package dev.aiauto.android.ui.overlay

/**
 * 功能用途：为悬浮窗（含 edge-light）提供专用 LifecycleOwner，并向 ViewTree 注册
 * LifecycleOwner、ViewModelStoreOwner、SavedStateRegistryOwner 三类 owner。
 *
 * 为什么需要：悬浮窗用 applicationContext 创建 ComposeView 并 attach 到 WindowManager，
 * applicationContext 不关联任何 Activity，ViewTreeLifecycleOwner / ViewModelStoreOwner /
 * SavedStateRegistryOwner 均为空，ComposeView attach 时会抛 IllegalStateException。
 * 抽成公共件，供 OverlayController 与 EdgeLightController 复用，避免各写一份。
 */

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 悬浮窗专用 LifecycleOwner：提供 ComposeView 挂载所需的最小三件套。
 * 创建时 performCreate，再由控制器转发 ON_START/ON_RESUME；销毁时 ON_DESTROY 并清理 store。
 */
internal class OverlayLifecycleOwner :
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

    /** 转发 Lifecycle 事件；ON_DESTROY 时清空 ViewModelStore。 */
    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
        if (event == Lifecycle.Event.ON_DESTROY) {
            store.clear()
        }
    }
}

/**
 * 通过反射为悬浮窗 View 注入三个 ViewTree owner。
 *
 * 为什么用反射：这些 ViewTree* 类在 lifecycle KMP 架构下可能不在编译 classpath，
 * 但运行时一定由 lifecycle-runtime 传递提供；反射调用其静态 set(View, Owner) 最稳，
 * 不依赖具体 Maven 坐标。反射失败时静默降级，由 ComposeView attach 的报错暴露。
 */
internal fun View.attachOverlayViewTreeOwners(owner: OverlayLifecycleOwner) {
    runCatching {
        val clazz = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
        clazz.getMethod("set", View::class.java, LifecycleOwner::class.java)
            .invoke(null, this, owner)
    }
    runCatching {
        val clazz = Class.forName("androidx.lifecycle.ViewTreeViewModelStoreOwner")
        clazz.getMethod("set", View::class.java, ViewModelStoreOwner::class.java)
            .invoke(null, this, owner)
    }
    runCatching {
        val clazz = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
        clazz.getMethod("set", View::class.java, SavedStateRegistryOwner::class.java)
            .invoke(null, this, owner)
    }
}
