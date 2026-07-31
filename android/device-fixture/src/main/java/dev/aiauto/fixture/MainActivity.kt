package dev.aiauto.fixture

/**
 * 功能用途：提供稳定语义节点的原生动作首页，并将每次动作映射为独立可机器断言的进程内状态。
 */

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ListView
import android.widget.TextView
import android.widget.ToggleButton

class MainActivity : Activity() {
    private val state = FixtureProcessState.current

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindInput()
        bindClick()
        bindLongPress()
        bindVerticalScroll()
        bindHorizontalSwipe()
        bindDialog()
        bindNavigation()
        bindSystemMarkers()
        bindReset()
        bindLegacyToggle()
        render()
    }

    override fun onResume() {
        super.onResume()
        state.recordForegroundReturn()
        render()
    }

    private fun bindInput() {
        findViewById<EditText>(R.id.text_input).apply {
            setText(state.inputValue)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    state.recordInput(text?.toString().orEmpty())
                    render()
                }

                override fun afterTextChanged(text: Editable?) = Unit
            })
        }
    }

    private fun bindClick() {
        findViewById<View>(R.id.click_target).setOnClickListener {
            state.recordClick()
            render()
        }
    }

    private fun bindLongPress() {
        findViewById<View>(R.id.long_press_target).setOnLongClickListener {
            state.recordLongPress()
            render()
            true
        }
    }

    private fun bindVerticalScroll() {
        val target = findViewById<ListView>(R.id.vertical_scroll_target)
        target.adapter = VerticalScrollAdapter()
        renderOffset(R.id.vertical_scroll_offset, "VERTICAL_OFFSET", 0)
        target.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

            override fun onScroll(
                view: AbsListView,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int,
            ) {
                val firstChild = view.getChildAt(0) ?: return
                val offset = VerticalListOffset.calculate(
                    firstVisiblePosition = firstVisibleItem,
                    firstChildTop = firstChild.top,
                    listPaddingTop = view.paddingTop,
                    itemExtent = firstChild.height,
                )
                renderOffset(R.id.vertical_scroll_offset, "VERTICAL_OFFSET", offset)
                if (offset > 0) {
                    state.recordVerticalScroll()
                }
                render()
            }
        })
    }

    private fun bindHorizontalSwipe() {
        val target = findViewById<HorizontalScrollView>(R.id.horizontal_swipe_target)
        renderOffset(R.id.horizontal_swipe_offset, "HORIZONTAL_OFFSET", target.scrollX)
        target.setOnScrollChangeListener { _: View, scrollX: Int, _: Int, oldScrollX: Int, _: Int ->
            if (scrollX != oldScrollX) {
                state.recordHorizontalSwipe()
                renderOffset(R.id.horizontal_swipe_offset, "HORIZONTAL_OFFSET", scrollX)
                render()
            }
        }
    }

    private fun bindDialog() {
        findViewById<View>(R.id.dialog_open_target).setOnClickListener {
            state.recordDialogOpened()
            renderDialogState(getString(R.string.dialog_open_state))
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title)
                .setMessage(R.string.dialog_message)
                .setNegativeButton(R.string.dialog_dismiss) { dialog, _ ->
                    state.recordDialogDismissed()
                    render()
                    dialog.dismiss()
                }
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        dialog.findViewById<TextView>(android.R.id.message)?.apply {
                            id = R.id.dialog_state
                            text = getString(R.string.dialog_open_state)
                            contentDescription = getString(R.string.dialog_state_description)
                        }
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                            id = R.id.dialog_dismiss_target
                            contentDescription = getString(R.string.dialog_dismiss_description)
                        }
                    }
                    dialog.show()
                }
        }
    }

    private fun bindNavigation() {
        findViewById<View>(R.id.open_second_page_target).setOnClickListener {
            startActivity(Intent(this, SecondActivity::class.java))
        }
    }

    private fun bindSystemMarkers() {
        bindSystemMarker(R.id.mark_home_target, "HOME")
        bindSystemMarker(R.id.mark_recents_target, "RECENTS")
        bindSystemMarker(R.id.mark_settings_target, "SETTINGS")
    }

    private fun bindSystemMarker(id: Int, kind: String) {
        findViewById<View>(id).setOnClickListener {
            state.recordExternalNavigation(kind)
            render()
        }
    }

    private fun bindReset() {
        findViewById<View>(R.id.reset_target).setOnClickListener {
            findViewById<ListView>(R.id.vertical_scroll_target).setSelection(0)
            findViewById<HorizontalScrollView>(R.id.horizontal_swipe_target).scrollTo(0, 0)
            state.reset()
            renderOffset(R.id.vertical_scroll_offset, "VERTICAL_OFFSET", 0)
            renderOffset(R.id.horizontal_swipe_offset, "HORIZONTAL_OFFSET", 0)
            findViewById<EditText>(R.id.text_input).setText("")
            findViewById<ToggleButton>(R.id.local_toggle).isChecked = false
            render()
        }
    }

    private fun bindLegacyToggle() {
        val toggle = findViewById<ToggleButton>(R.id.local_toggle)
        toggle.setOnCheckedChangeListener { _, isChecked ->
            findViewById<TextView>(R.id.status_text).text = if (isChecked) {
                getString(R.string.status_on)
            } else {
                getString(R.string.status_off)
            }
        }
    }

    private fun render() {
        text(R.id.page_state, "PAGE:MAIN")
        text(R.id.input_state, "INPUT:${state.inputValue}")
        text(R.id.click_state, "CLICK:${state.clickCount}")
        text(R.id.long_press_state, "LONG_PRESS:${state.longPressCount}")
        text(R.id.vertical_scroll_state, "VERTICAL_SCROLL:${state.verticalScrollCount}")
        text(R.id.horizontal_swipe_state, "HORIZONTAL_SWIPE:${state.horizontalSwipeCount}")
        renderDialogState(if (state.dialogDismissed) "DIALOG:DISMISSED" else "DIALOG:READY")
        text(R.id.system_pending_state, "SYSTEM_PENDING:${state.pendingExternalNavigation}")
        renderSystemReturns()
        text(R.id.reset_state, "RESET:DONE")
    }

    private fun renderDialogState(value: String) {
        text(R.id.dialog_state, value)
    }

    private fun renderSystemReturns() {
        renderSystemReturn(R.id.home_return_state, "HOME")
        renderSystemReturn(R.id.recents_return_state, "RECENTS")
        renderSystemReturn(R.id.settings_return_state, "SETTINGS")
    }

    private fun renderSystemReturn(id: Int, kind: String) {
        text(
            id,
            if (state.hasReturnedFromSystem && state.lastExternalNavigation == kind) {
                "SYSTEM_RETURN:$kind"
            } else {
                "SYSTEM_RETURN:NONE"
            },
        )
    }

    private fun text(id: Int, value: String) {
        findViewById<TextView>(id).text = value
    }

    private fun renderOffset(id: Int, prefix: String, offset: Int) {
        text(id, "$prefix:$offset")
    }

    /**
     * 功能用途：生成固定数量、固定高度且语义稳定的本地列表行，不读取或持久化外部数据。
     */
    private inner class VerticalScrollAdapter : BaseAdapter() {
        override fun getCount(): Int = VERTICAL_ITEM_COUNT

        override fun getItem(position: Int): Int = position

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val label = (convertView as? TextView) ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.vertical_scroll_item, parent, false) as TextView
            val itemNumber = position + 1
            label.text = getString(R.string.vertical_scroll_item_label, itemNumber)
            label.contentDescription = getString(
                R.string.vertical_scroll_item_description,
                itemNumber,
            )
            return label
        }
    }

    private companion object {
        const val VERTICAL_ITEM_COUNT = 12
    }
}

/**
 * 功能用途：承载第二个原生页面并保留平台 Activity Back 栈，供自动化验证一级返回行为。
 */
class SecondActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_second)
        findViewById<View>(R.id.open_third_page_target).setOnClickListener {
            startActivity(Intent(this, ThirdActivity::class.java))
        }
    }
}

/**
 * 功能用途：承载第三个原生页面，依赖平台 Back 返回第二页以验证多级导航栈。
 */
class ThirdActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_third)
    }
}
