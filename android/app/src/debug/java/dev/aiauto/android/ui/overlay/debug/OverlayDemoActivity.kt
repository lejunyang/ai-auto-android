package dev.aiauto.android.ui.overlay.debug

/**
 * 功能用途：debug-only 悬浮窗演练场 Activity，脱离会话端到端确定性验证悬浮窗各状态。
 *
 * 职责：
 * - 持有 OverlayController 实例，提供按钮切换 Running/Paused/Completed/Failed/展开/收起。
 * - 仅供 debug 源集编译，release 不包含此 Activity。
 * - 纯 UI harness：不自动操作支付/登录/系统设置，符合 AGENTS 人工授权边界。
 */

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import dev.aiauto.android.ui.overlay.EdgeLightController
import dev.aiauto.android.ui.overlay.EdgeLightMode
import dev.aiauto.android.ui.overlay.OverlayActions
import dev.aiauto.android.ui.overlay.OverlayController
import dev.aiauto.android.ui.overlay.OverlaySessionState

class OverlayDemoActivity : Activity() {

    private var controller: OverlayController? = null

    // edge-light 控制器：与悬浮窗状态联动，便于确定性截图边缘光效。
    private var edge: EdgeLightController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        controller = OverlayController(this).apply {
            setActions(demoActions())
            show()
        }

        edge = EdgeLightController(this)

        // 每个按钮一键设置「会话状态 + 展开/收起形态」，使各状态可被脚本确定性复现与截图。
        val buttons = listOf(
            "Running 展开" to { setState(OverlaySessionState.Running("演示任务")); controller?.expand(); setEdge(EdgeLightMode.ACTIVE) },
            "等待确认(金边)" to { setEdge(EdgeLightMode.WAITING) },
            "收起为边缘圆" to { controller?.collapse() },
            "重新展开" to { controller?.expand(); setEdge(EdgeLightMode.ACTIVE) },
            "暂停态" to { setState(OverlaySessionState.Paused("演示任务")); controller?.expand(); setEdge(EdgeLightMode.PAUSED) },
            "继续/Running" to { setState(OverlaySessionState.Running("演示任务")); controller?.expand(); setEdge(EdgeLightMode.ACTIVE) },
            "停止态" to { setState(OverlaySessionState.Stopped("演示任务")); controller?.expand(); setEdge(EdgeLightMode.OFF) },
            "完成结果态" to { setState(OverlaySessionState.Completed("演示任务", "任务已完成")); controller?.expand(); setEdge(EdgeLightMode.OFF) },
            "失败态" to { setState(OverlaySessionState.Failed("演示任务", "模拟失败原因")); controller?.expand(); setEdge(EdgeLightMode.OFF) },
            "隐藏悬浮窗" to { controller?.hide(); setEdge(EdgeLightMode.OFF) },
            "显示悬浮窗" to { controller?.show() },
            // 模拟 AI 截图前后的遮挡/恢复，验证光效不进入截图（仅 debug 演练用）。
            "截图隐藏(模拟)" to { edge?.suppressForCapture() },
            "截图恢复(模拟)" to { edge?.restoreAfterCapture() },
        )

        buttons.forEach { (label, action) ->
            Button(this).apply {
                text = label
                setOnClickListener {
                    action()
                    Toast.makeText(this@OverlayDemoActivity, label, Toast.LENGTH_SHORT).show()
                }
                layout.addView(this)
            }
        }

        setContentView(layout)
    }

    private fun setState(state: OverlaySessionState) {
        controller?.updateState(state)
    }

    // 切换 edge-light 模式，与悬浮窗状态保持同一演示口径。
    private fun setEdge(mode: EdgeLightMode) {
        edge?.setMode(mode)
    }

    private fun demoActions() = object : OverlayActions {
        override fun onPause() { setState(OverlaySessionState.Paused("演示任务")) }
        override fun onResume() { setState(OverlaySessionState.Running("演示任务")) }
        override fun onStop() { setState(OverlaySessionState.Stopped("演示任务")) }
        override fun onExpand() { Toast.makeText(this@OverlayDemoActivity, "已展开", Toast.LENGTH_SHORT).show() }
        override fun onCollapse() { Toast.makeText(this@OverlayDemoActivity, "已收起", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        controller?.destroy()
        controller = null
        edge?.destroy()
        edge = null
        super.onDestroy()
    }
}
