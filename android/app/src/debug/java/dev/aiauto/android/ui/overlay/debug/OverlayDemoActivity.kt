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
import dev.aiauto.android.ui.overlay.OverlayActions
import dev.aiauto.android.ui.overlay.OverlayController
import dev.aiauto.android.ui.overlay.OverlaySessionState

class OverlayDemoActivity : Activity() {

    private var controller: OverlayController? = null

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

        // 每个按钮一键设置「会话状态 + 展开/收起形态」，使各状态可被脚本确定性复现与截图。
        val buttons = listOf(
            "Running 展开" to { setState(OverlaySessionState.Running("演示任务")); controller?.expand() },
            "收起为边缘圆" to { controller?.collapse() },
            "重新展开" to { controller?.expand() },
            "暂停态" to { setState(OverlaySessionState.Paused("演示任务")); controller?.expand() },
            "继续/Running" to { setState(OverlaySessionState.Running("演示任务")); controller?.expand() },
            "停止态" to { setState(OverlaySessionState.Stopped("演示任务")); controller?.expand() },
            "完成结果态" to { setState(OverlaySessionState.Completed("演示任务", "任务已完成")); controller?.expand() },
            "失败态" to { setState(OverlaySessionState.Failed("演示任务", "模拟失败原因")); controller?.expand() },
            "隐藏悬浮窗" to { controller?.hide() },
            "显示悬浮窗" to { controller?.show() },
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
        super.onDestroy()
    }
}
