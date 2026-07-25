package dev.aiauto.fixture

/**
 * 功能用途：确保已完整可见的内层纵向目标独占拖动序列，防止外层页面容器截获测试手势。
 */

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView
import kotlin.math.roundToInt

class DeterministicScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {
    private var lastTouchY: Float? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            -> parent?.requestDisallowInterceptTouchEvent(true)
        }

        val handled = super.dispatchTouchEvent(event)
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val previous = lastTouchY ?: event.y
                val delta = (previous - event.y).roundToInt()
                if (delta != 0) {
                    scrollBy(0, delta)
                }
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                lastTouchY = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
