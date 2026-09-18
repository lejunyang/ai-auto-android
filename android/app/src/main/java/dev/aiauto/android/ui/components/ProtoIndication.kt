package dev.aiauto.android.ui.components

/**
 * 功能用途：自定义按压反馈系统，替代 Material 水波纹。
 *
 * 设计原则：克制反馈——按压时元素轻微缩放 scale≈0.98，松手即回，120-160ms，
 * 无扩散水波纹。提供 Modifier.protoClickable{} 统一点击入口。
 *
 * 所有用户可见可点击元素统一使用此入口，不再使用 Material 水波纹。
 */

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

/**
 * 统一点击入口：带自定义按压反馈（scale 0.98，140ms）。
 *
 * @param onClick 点击回调
 * @param enabled 是否可点击（false 时无反馈）
 * @param pressScale 按压时缩放比例，默认 0.98
 */
@Composable
fun Modifier.protoClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    pressScale: Float = 0.98f,
): Modifier {
    // interactionSource 用于检测按压状态。
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // 按压时 scale 动画，140ms 快速回。
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressScale else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "protoPressScale",
    )
    return this
        .scale(scale)
        .clickable(
            interactionSource = interactionSource,
            indication = null, // 不用 Material 水波纹
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * 无反馈点击入口（用于卡片整体可点击但不需要按压动画的场景）。
 */
@Composable
fun Modifier.protoClickableNoFeedback(
    onClick: () -> Unit,
    enabled: Boolean = true,
): Modifier {
    return this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}
