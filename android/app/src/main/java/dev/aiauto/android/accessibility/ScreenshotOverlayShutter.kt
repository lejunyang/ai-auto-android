package dev.aiauto.android.accessibility

/**
 * 功能用途：截图隐私遮挡接口。
 *
 * AI 通过无障碍 takeScreenshot 获取整屏画面用于感知；装饰性系统浮层（如会话运行时的
 * 边缘呼吸光效）不属于被操作 App 的内容，截图期间需临时隐藏，避免 AI 把光效误判为界面元素。
 * 截图结束（成功、失败或取消）后恢复显示。
 */
interface ScreenshotOverlayShutter {
    /** 截图开始前调用：隐藏装饰浮层。 */
    fun suppressForCapture()

    /** 截图结束后调用：恢复装饰浮层。 */
    fun restoreAfterCapture()
}

/**
 * 功能用途：进程内 [ScreenshotOverlayShutter] 持有者，解耦无障碍截图层与具体浮层控制器的生命周期。
 *
 * 浮层控制器创建窗口时注册、移除窗口时注销；截图方在截图前后经此取得当前 shutter。
 */
object ScreenshotOverlayShutterHolder {
    @Volatile
    private var current: ScreenshotOverlayShutter? = null

    /** 注册当前浮层 shutter，后注册者覆盖先前实例。 */
    fun register(shutter: ScreenshotOverlayShutter) {
        current = shutter
    }

    /** 仅当注册者是同一实例时注销，避免误删更新的浮层。 */
    fun unregister(shutter: ScreenshotOverlayShutter) {
        if (current === shutter) current = null
    }

    /** 获取当前 shutter，无浮层时返回 null（截图流程按无遮挡处理）。 */
    fun get(): ScreenshotOverlayShutter? = current
}
