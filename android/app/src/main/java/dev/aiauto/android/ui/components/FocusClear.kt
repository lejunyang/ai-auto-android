package dev.aiauto.android.ui.components

/**
 * 功能用途：提供全局点击输入区域外清除焦点并收起输入法的共享 Modifier。
 * 所有含文本输入的页面/弹窗必须在根容器应用此 Modifier，避免键盘无法收起。
 * 子元素（按钮/卡片/输入框）消费点击时不会触发，因此不会误吞交互。
 */

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * 点击空白区域时清除焦点并收起输入法。
 *
 * 在页面根 Composable 上调用：`Modifier.clearFocusOnTapOutside()`。
 * 内部使用 detectTapGestures(onTap)，子元素先消费点击时不会冒泡到本层，
 * 因此按钮/卡片/输入框的点击不受影响。
 */
@Composable
fun Modifier.clearFocusOnTapOutside(): Modifier {
    val focusManager: FocusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return this.then(
        Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = {
                focusManager.clearFocus()
                keyboard?.hide()
            })
        },
    )
}

/**
 * 包裹内容并在空白区域点击时清除焦点、收起输入法。
 * 用于二级页/弹窗根容器，替代每页手写 pointerInput。
 */
@Composable
fun FocusClearArea(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.clearFocusOnTapOutside(),
        content = content,
    )
}
