package dev.aiauto.android.accessibility.snapshot

/**
 * 功能用途：把 AccessibilityService.windows 枚举为有界的多窗口快照，
 * 使对话框、输入法、悬浮窗、分屏与画中画中的节点可被定位。
 *
 * 为什么必须有这一层：此前所有节点来源都取 rootInActiveWindow，
 * 它只返回「当前活动窗口」的根。弹出对话框、输入法候选栏、系统悬浮窗
 * 在多数场景下不是活动窗口，于是任何选择器在这些界面上都必然
 * SELECTOR_NOT_FOUND——不是选择器不准，而是节点根本没进入候选集合。
 */

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ScreenBounds
import dev.aiauto.android.accessibility.model.UiWindowSnapshot
import dev.aiauto.android.accessibility.model.WindowKind

class WindowSnapshotter(
    private val snapshotter: AccessibilitySnapshotter = AccessibilitySnapshotter(),
    private val maxWindows: Int = DEFAULT_MAX_WINDOWS,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    /**
     * 枚举当前可见窗口并逐个生成快照。
     *
     * 单个窗口快照失败不会让整次枚举失败：窗口在遍历期间被关闭是常态
     * （对话框消失、输入法收起），此时该窗口应被跳过而不是让调用方
     * 拿到一个错误。只有「一个窗口都拿不到」才是真正的失败，
     * 由调用方按 WINDOW_UNAVAILABLE 处理。
     *
     * 返回顺序按 layer 降序（最上层在前），layer 相同时活动窗口优先。
     * 坐标动作必须命中最上层窗口，顺序即优先级。
     */
    fun snapshotWindows(service: AccessibilityService): List<UiWindowSnapshot> {
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        val snapshots = ArrayList<UiWindowSnapshot>(minOf(windows.size, maxWindows))
        for (window in windows) {
            if (snapshots.size >= maxWindows) {
                break
            }
            val root = runCatching { window.root }.getOrNull() ?: continue
            try {
                val snapshot = snapshotter.snapshot(root)
                if (snapshot is AccessibilityResult.Success) {
                    snapshots += UiWindowSnapshot(
                        windowId = window.id,
                        kind = window.toWindowKind(),
                        layer = window.layer,
                        active = window.isActive,
                        focused = window.isFocused,
                        bounds = window.screenBounds(),
                        root = snapshot.value,
                    )
                }
            } finally {
                root.recycleSafely()
            }
        }
        return snapshots.sortedWith(
            compareByDescending<UiWindowSnapshot> { it.layer }
                .thenByDescending { it.active },
        )
    }

    /**
     * 按窗口 id 取回平台窗口的根节点，调用方负责回收。
     *
     * 动作执行时必须重新取根而不能复用快照期间的节点：快照到执行之间
     * 界面可能已经变化，沿用旧节点会对已失效的 View 派发动作。
     */
    fun rootOf(service: AccessibilityService, windowId: Int): AccessibilityNodeInfo? =
        runCatching { service.windows }
            .getOrNull()
            .orEmpty()
            .firstOrNull { it.id == windowId }
            ?.let { window -> runCatching { window.root }.getOrNull() }

    private fun AccessibilityWindowInfo.screenBounds(): ScreenBounds {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return ScreenBounds(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
        )
    }

    /**
     * 把平台窗口类型映射为领域类别。
     *
     * 画中画优先于 APPLICATION 判定：平台没有画中画窗口类型，
     * isInPictureInPictureMode 是 APPLICATION 窗口上的一个属性，
     * 若按 type 判定会把画中画误报成普通应用窗口。
     */
    private fun AccessibilityWindowInfo.toWindowKind(): WindowKind {
        if (
            sdkInt >= Build.VERSION_CODES.O &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { isInPictureInPictureMode }.getOrDefault(false)
        ) {
            return WindowKind.PICTURE_IN_PICTURE
        }
        return when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APPLICATION
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.INPUT_METHOD
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.SYSTEM
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ->
                WindowKind.ACCESSIBILITY_OVERLAY

            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ->
                WindowKind.SPLIT_SCREEN_DIVIDER

            AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY ->
                WindowKind.MAGNIFICATION_OVERLAY

            else -> WindowKind.UNKNOWN
        }
    }

    private companion object {
        /**
         * 单次枚举的窗口上限。
         *
         * 取 12：正常场景最多是「主应用 + 对话框 + 输入法 + 状态栏 + 导航栏 +
         * 分屏对端 + 分屏分隔条 + 画中画」共 8 个，留 4 个余量给 OEM 悬浮窗。
         * 上限存在的意义是防止异常设备上窗口列表膨胀导致快照耗时失控。
         */
        const val DEFAULT_MAX_WINDOWS = 12
    }
}
