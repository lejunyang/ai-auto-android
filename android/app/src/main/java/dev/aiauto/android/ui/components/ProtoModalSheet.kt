package dev.aiauto.android.ui.components

/**
 * 功能用途：提供可复用的底部弹窗（ModalBottomSheet 风格）组件。
 * 按原型 token 自绘：scrim 半透明深蓝遮罩 + 顶部把手 + 白底圆角底部面板 +
 * 弹出/关闭动画（slide up/down + scrim fade in/out），点遮罩/返回键关闭。
 * 不直接用 Material3 ModalBottomSheet（样式难定制），完全自绘保证对齐原型。
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import dev.aiauto.android.ui.theme.AppColors

/**
 * 通用底部弹窗。
 *
 * @param visible 是否显示。
 * @param onDismiss 点遮罩/返回键/关闭按钮时回调。
 * @param title 弹窗标题（原型 .modal h2 20sp）。
 * @param content 弹窗内容区，自动滚动，已应用水平 padding。
 */
@Composable
fun ProtoModalSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 动画时长与原型一致：弹出 slide up + scrim fade in。
    val animSpec = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 280)
    val fadeSpec = tween<Float>(durationMillis = 280)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = fadeSpec),
        exit = fadeOut(animationSpec = fadeSpec),
    ) {
        // scrim 遮罩：半透明深蓝 #16233755，点遮罩关闭。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x55162337))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        ) {
            // 底部面板：自绘，避免点击穿透到遮罩。
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(animationSpec = animSpec) { it },
                exit = slideOutVertically(animationSpec = animSpec) { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = AppColors.SurfaceMuted,
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        )
                        .imePadding()
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    // 顶部拖拽把手：35x4 圆角9 背景#d1d9e5。
                    Box(
                        modifier = Modifier
                            .width(35.dp)
                            .height(4.dp)
                            .background(
                                color = Color(0xFFD1D9E5),
                                shape = RoundedCornerShape(9.dp),
                            )
                            .align(Alignment.CenterHorizontally),
                    )
                    // 标题行：标题 + 右上关闭。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.Ink,
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = AppColors.Muted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    // 内容区：可滚动。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 12.dp),
                        content = content,
                    )
                }
            }
        }
    }
}
