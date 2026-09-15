package dev.aiauto.android.accessibility

/**
 * 功能用途：为动作面扩展的设备验收提供可编辑、可调节、可展开与长列表控件，
 * 仅用于 debug 变体且不进入 release 制品。
 *
 * 为什么单独成一个 Activity 而不扩充 ScreenshotTestActivity：
 * 后者已被截图、会话停止、用户触摸与 N45 视觉四组测试共用，
 * 往里加控件会改变其节点树结构与坐标，可能让那些既有测试的断言失效。
 * 动作面需要的控件（EditText / SeekBar / 可展开容器 / 长列表）与截图验收
 * 无关，分开更安全也更清晰。
 *
 * 界面刻意不含任何敏感字段：文本输入框的内容会进入无障碍快照，
 * 而快照的脱敏规则会把含「密码」「验证码」等字样的节点整体屏蔽，
 * 那样反而无法验证文本选区与复制粘贴。
 */

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class ActionSurfaceFixtureActivity : Activity() {
    private lateinit var editable: EditText
    private lateinit var pasteTarget: EditText
    private lateinit var seekBar: SeekBar
    private lateinit var collapsible: LinearLayout
    private lateinit var collectionView: ListView
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ScrollView(this).apply {
                isFillViewport = true
                contentDescription = SCROLL_CONTAINER_DESCRIPTION
                addView(buildContent())
            },
        )
    }

    private fun buildContent(): ViewGroup = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        addView(
            TextView(context).apply {
                text = "Action surface fixture"
                textSize = 20f
                contentDescription = "Action surface fixture"
            },
        )

        // 文本输入框承载 A5（文本选区）与 A6（复制/剪切/粘贴）。
        // 预填内容而不留空：SET_SELECTION 需要已有文本才能划出区间，
        // COPY 也需要选区非空才会真正写入剪贴板。
        addView(
            EditText(context).apply {
                setText(INITIAL_TEXT)
                contentDescription = EDITABLE_DESCRIPTION
                editable = this
            },
        )

        // 第二个输入框用于验证粘贴：粘贴到源框无法区分「粘贴成功」
        // 与「内容本来就在那」。
        addView(
            EditText(context).apply {
                setText("")
                contentDescription = PASTE_TARGET_DESCRIPTION
                pasteTarget = this
            },
        )

        // SeekBar 承载 A9 的 SET_PROGRESS。
        addView(
            SeekBar(context).apply {
                max = SEEK_MAX
                progress = 0
                contentDescription = SEEK_BAR_DESCRIPTION
                seekBar = this
            },
        )

        // CheckBox 承载 A9 的 SELECT 与 FOCUS。
        addView(
            CheckBox(context).apply {
                text = "Selectable option"
                contentDescription = CHECKBOX_DESCRIPTION
            },
        )

        // 可展开容器承载 A9 的 EXPAND / COLLAPSE。
        // 平台的 ACTION_EXPAND / ACTION_COLLAPSE 需要 View 自己在
        // onInitializeAccessibilityNodeInfo 里声明，因此用自定义 View 而非
        // 现成控件——系统控件里带这两个动作的极少。
        addView(
            ExpandableSectionView(context) { expanded ->
                setStatus(if (expanded) "EXPANDED" else "COLLAPSED")
            }.apply {
                contentDescription = EXPANDABLE_DESCRIPTION
            },
        )

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                contentDescription = "Collapsible body"
                addView(
                    TextView(context).apply {
                        text = "Collapsible body content"
                    },
                )
                collapsible = this
            },
        )

        // ListView 承载 A9 的 SCROLL_TO_POSITION。
        //
        // 位置必须在填充行**之前**：填充行共 24 行 × 48dp 远超一屏，
        // 放在它们之后时 ListView 落在视口外，节点不进入无障碍树，
        // 选择器会以 SELECTOR_NOT_FOUND 失败（已实测踩到）。
        //
        // 用平台自带的 ListView 而非 RecyclerView：后者需要新增 androidx 依赖，
        // 而依赖会进入所有变体，仅为测试夹具引入生产依赖不划算。
        // ListView 继承 AbsListView，平台已为其提供 CollectionInfo，
        // 这正是 ACTION_SCROLL_TO_POSITION 生效的前提。
        addView(
            ListView(context).apply {
                contentDescription = COLLECTION_DESCRIPTION
                adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_list_item_1,
                    (0 until COLLECTION_ROWS).map { "Collection row $it" },
                )
                // 固定高度：ListView 在 LinearLayout 里若用 wrap_content
                // 只会量出一行，那样滚动与按位置定位都无从发生。
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPixels(COLLECTION_HEIGHT_DP),
                )
                collectionView = this
            },
        )

        // 长列表承载 A3（scrollIntoView）。项数必须多到首屏放不下，
        // 否则目标一开始就可见，测不出滚动逻辑。
        repeat(FILLER_ROWS) { index ->
            addView(
                TextView(context).apply {
                    text = "Filler row $index"
                    contentDescription = "Filler row $index"
                    minimumHeight = dpToPixels(FILLER_ROW_HEIGHT_DP)
                },
            )
        }

        // 滚动目标放在最后：它必须在初始视口之外。
        addView(
            Button(context).apply {
                text = "Scroll target"
                contentDescription = SCROLL_TARGET_DESCRIPTION
                setOnClickListener { setStatus("SCROLL_TARGET_CLICKED") }
            },
        )

        addView(
            TextView(context).apply {
                text = READY_STATUS
                contentDescription = READY_STATUS
                statusView = this
            },
        )
    }

    private fun setStatus(status: String) {
        statusView.text = status
        statusView.contentDescription = status
    }

    /** 供设备测试读取输入框当前文本，断言复制粘贴是否真的生效。 */
    fun editableText(): String = editable.text?.toString().orEmpty()

    /** 供设备测试读取粘贴目标框文本，断言粘贴结果。 */
    fun pasteTargetText(): String = pasteTarget.text?.toString().orEmpty()

    /** 供设备测试读取当前选区，断言 SET_SELECTION 是否生效。 */
    fun selection(): Pair<Int, Int> =
        editable.selectionStart to editable.selectionEnd

    /** 供设备测试读取进度值，断言 SET_PROGRESS 是否生效。 */
    fun progress(): Int = seekBar.progress

    /** 供设备测试读取状态文本，断言展开折叠等无返回值动作的实际效果。 */
    fun status(): String = statusView.text?.toString().orEmpty()

    /**
     * 供设备测试读取 ListView 当前可见的首行下标。
     *
     * SCROLL_TO_POSITION 只返回布尔值，不读回首行下标就无法区分
     * 「动作被接受」与「真的滚到了那一行」。
     */
    fun collectionFirstVisiblePosition(): Int = collectionView.firstVisiblePosition

    private fun dpToPixels(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EDITABLE_DESCRIPTION = "Action surface editable"
        const val PASTE_TARGET_DESCRIPTION = "Action surface paste target"
        const val SEEK_BAR_DESCRIPTION = "Action surface seek bar"
        const val CHECKBOX_DESCRIPTION = "Action surface checkbox"
        const val EXPANDABLE_DESCRIPTION = "Action surface expandable"
        const val COLLECTION_DESCRIPTION = "Action surface collection"
        const val SCROLL_TARGET_DESCRIPTION = "Action surface scroll target"
        const val SCROLL_CONTAINER_DESCRIPTION = "Action surface scroll container"
        const val READY_STATUS = "ACTION_SURFACE_READY"
        const val INITIAL_TEXT = "abcdefghij"
        const val SEEK_MAX = 100

        /**
         * 填充行数与行高。
         *
         * 取 24 行 × 48dp：在 320x640 的测试设备上远超一屏，
         * 保证滚动目标初始不可见，scrollIntoView 必须真的滚动才能命中。
         */
        private const val FILLER_ROWS = 24
        private const val FILLER_ROW_HEIGHT_DP = 48

        /**
         * ListView 的行数与可视高度。
         *
         * 40 行 × 120dp 视口：视口只容纳约 3 行，因此 SCROLL_TO_POSITION 到
         * 第 20 行必然改变 firstVisiblePosition，断言才有区分度。
         */
        private const val COLLECTION_ROWS = 40
        private const val COLLECTION_HEIGHT_DP = 120
    }
}

/**
 * 声明 ACTION_EXPAND / ACTION_COLLAPSE 的最小容器。
 *
 * 平台不会为普通 View 自动提供这两个动作，必须自己在
 * onInitializeAccessibilityNodeInfo 里加入并在 performAccessibilityAction
 * 里处理，否则节点快照上不会出现 EXPAND / COLLAPSE。
 */
private class ExpandableSectionView(
    context: android.content.Context,
    private val onToggle: (Boolean) -> Unit,
) : TextView(context) {
    private var expanded = false

    init {
        text = "Expandable section"
        isFocusable = true
    }

    override fun onInitializeAccessibilityNodeInfo(
        info: android.view.accessibility.AccessibilityNodeInfo,
    ) {
        super.onInitializeAccessibilityNodeInfo(info)
        // 按当前状态只暴露有意义的那一个：已展开时再 EXPAND 没有语义，
        // 同时暴露两者会让「动作被接受」无法区分是否真的改变了状态。
        if (expanded) {
            info.addAction(
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE,
            )
        } else {
            info.addAction(
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND,
            )
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val expandId =
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id
        val collapseId =
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id
        return when (action) {
            expandId -> {
                expanded = true
                onToggle(true)
                announceStateChange()
                true
            }

            collapseId -> {
                expanded = false
                onToggle(false)
                announceStateChange()
                true
            }

            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    /**
     * 通知无障碍框架本节点的可用动作已变化。
     *
     * 缺这一步时框架会继续沿用缓存的节点信息，展开之后再查仍然只看到
     * ACTION_EXPAND，于是后续 COLLAPSE 会被判为「节点不支持」（已实测）。
     * 状态变化必须显式广播，框架不会自己重新读 onInitializeAccessibilityNodeInfo。
     */
    private fun announceStateChange() {
        sendAccessibilityEvent(
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )
    }
}
