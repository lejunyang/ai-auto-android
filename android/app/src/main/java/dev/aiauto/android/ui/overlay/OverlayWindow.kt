package dev.aiauto.android.ui.overlay

/**
 * 功能用途：受控悬浮窗的 Compose UI 组合层。
 *
 * 职责：
 * - 以纯 Composable 形式渲染悬浮窗内容，不持有 WindowManager 或 Context，可独立 Preview。
 * - 提供两种形态：展开态（卡片，含状态图标、任务名、操作按钮）与收起态（48dp 圆形图标）。
 * - 通过 [OverlayActions] 把用户操作回调给外层控制器，自身不改变会话状态。
 *
 * 设计边界：本文件只负责渲染，权限、WindowManager 添加/移除、生命周期由 OverlayController 负责。
 */

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import dev.aiauto.android.ui.theme.AiAutoAndroidTheme

/**
 * 悬浮窗展示的会话状态。
 *
 * - [Idle]：无任务，悬浮窗仅作指示图标。
 * - [Running]：任务执行中，展示任务名并提供暂停/停止。
 * - [Paused]：任务已暂停，提供继续/停止。
 * - [Completed]：任务完成，展示结果摘要。
 * - [Failed]：任务失败，展示错误信息。
 */
sealed interface OverlaySessionState {
    data object Idle : OverlaySessionState
    data class Running(val task: String) : OverlaySessionState
    data class Paused(val task: String) : OverlaySessionState
    data class Completed(val task: String, val summary: String) : OverlaySessionState
    data class Failed(val task: String, val error: String) : OverlaySessionState
}

/**
 * 悬浮窗对外暴露的操作回调。由会话控制方实现，悬浮窗 UI 只负责触发。
 */
interface OverlayActions {
    /** 暂停当前运行中的任务。 */
    fun onPause()

    /** 继续已暂停的任务。 */
    fun onResume()

    /** 停止当前任务。 */
    fun onStop()

    /** 从收起态展开为卡片。 */
    fun onExpand()

    /** 从卡片收起为小图标。 */
    fun onCollapse()
}

/**
 * 悬浮窗根 Composable。
 *
 * @param state 当前会话状态，决定图标与按钮组合。
 * @param expanded 是否为展开卡片态；false 时渲染 48dp 圆形收起图标。
 * @param actions 用户操作回调。
 * @param modifier 外层修饰，由控制器传入以约束整体尺寸。
 */
@Composable
fun OverlayWindowContent(
    state: OverlaySessionState,
    expanded: Boolean,
    actions: OverlayActions,
    modifier: Modifier = Modifier,
) {
    // 收起态：48dp 圆形状态图标，点击展开。
    if (!expanded) {
        CollapsedBadge(state = state, actions = actions, modifier = modifier)
        return
    }

    // 展开态：卡片式布局。
    ExpandedCard(state = state, actions = actions, modifier = modifier)
}

/**
 * 收起态：48dp 圆形图标，展示当前会话状态；运行中图标持续旋转。
 */
@Composable
private fun CollapsedBadge(
    state: OverlaySessionState,
    actions: OverlayActions,
    modifier: Modifier = Modifier,
) {
    val (icon, tint) = rememberStatusVisual(state)
    val rotation = if (state is OverlaySessionState.Running) rotatingDegrees() else 0f

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable { actions.onExpand() },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = "悬浮窗状态",
                tint = tint,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotation),
            )
        }
    }
}

/**
 * 展开态卡片：圆角 16dp、带阴影，包含状态图标、任务名、结果摘要与操作按钮。
 */
@Composable
private fun ExpandedCard(
    state: OverlaySessionState,
    actions: OverlayActions,
    modifier: Modifier = Modifier,
) {
    val (icon, tint) = rememberStatusVisual(state)
    val rotation = if (state is OverlaySessionState.Running) rotatingDegrees() else 0f

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier.width(280.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 顶部行：状态图标 + 任务名 + 收起按钮。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(rotation),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = taskTitle(state),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // 收起按钮：任意状态下都可收回小图标。
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "收起悬浮窗",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable { actions.onCollapse() }
                        .padding(2.dp),
                )
            }

            // 摘要/错误信息（终态或失败时展示）。
            summaryText(state)?.let { summary ->
                Spacer(Modifier.size(8.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = summaryColor(state),
                )
            }

            // 操作按钮区：按状态渲染不同按钮组合。
            Spacer(Modifier.size(12.dp))
            ActionRow(state = state, actions = actions)
        }
    }
}

/**
 * 根据会话状态渲染操作按钮行。
 * - 运行中：暂停 + 停止
 * - 已暂停：继续 + 停止
 * - 完成/失败：关闭（收起）
 * - 空闲：收起
 */
@Composable
private fun ActionRow(state: OverlaySessionState, actions: OverlayActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is OverlaySessionState.Running -> {
                OutlinedButton(onClick = { actions.onPause() }) { Text("暂停") }
                OutlinedButton(onClick = { actions.onStop() }) { Text("停止") }
            }

            is OverlaySessionState.Paused -> {
                OutlinedButton(onClick = { actions.onResume() }) { Text("继续") }
                OutlinedButton(onClick = { actions.onStop() }) { Text("停止") }
            }

            is OverlaySessionState.Completed,
            is OverlaySessionState.Failed,
            -> {
                // 终态：关闭即收起悬浮窗，保留小图标便于回看结果。
                OutlinedButton(onClick = { actions.onCollapse() }) { Text("关闭") }
            }

            is OverlaySessionState.Idle -> {
                TextButton(onClick = { actions.onCollapse() }) { Text("收起") }
            }
        }
    }
}

/**
 * 计算状态对应的图标与着色。
 * 运行中使用旋转刷新图标，暂停用暂停图标，完成用对勾，失败用警告图标。
 */
@Composable
private fun rememberStatusVisual(state: OverlaySessionState): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error

    return when (state) {
        is OverlaySessionState.Idle -> Icons.Filled.PlayArrow to onSurfaceVariant
        is OverlaySessionState.Running -> Icons.Filled.Refresh to primary
        is OverlaySessionState.Paused -> Icons.Filled.Pause to primary
        is OverlaySessionState.Completed -> Icons.Filled.CheckCircle to primary
        is OverlaySessionState.Failed -> Icons.Filled.ErrorOutline to error
    }
}

/**
 * 运行中状态的无限旋转角度动画，1.2 秒一周。
 */
@Composable
private fun rotatingDegrees(): Float {
    val transition = rememberInfiniteTransition(label = "overlay_rotate")
    val degrees by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "overlay_rotate_degrees",
    )
    return degrees
}

/** 顶部任务标题：空闲态显示默认文案，其余显示任务名。 */
private fun taskTitle(state: OverlaySessionState): String = when (state) {
    is OverlaySessionState.Idle -> "AI 自动化"
    is OverlaySessionState.Running -> state.task.ifBlank { "正在执行任务" }
    is OverlaySessionState.Paused -> state.task.ifBlank { "任务已暂停" }
    is OverlaySessionState.Completed -> state.task.ifBlank { "任务完成" }
    is OverlaySessionState.Failed -> state.task.ifBlank { "任务失败" }
}

/** 摘要文本：仅终态/失败返回内容，运行中与暂停返回 null。 */
private fun summaryText(state: OverlaySessionState): String? = when (state) {
    is OverlaySessionState.Completed -> state.summary.ifBlank { "任务已完成" }
    is OverlaySessionState.Failed -> state.error.ifBlank { "执行失败" }
    else -> null
}

/** 摘要文字颜色：失败用错误色，其余用次级文字色。 */
@Composable
private fun summaryColor(state: OverlaySessionState): androidx.compose.ui.graphics.Color =
    if (state is OverlaySessionState.Failed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

/** 空实现：预览与测试时使用，避免调用真实会话操作。 */
private object NoopActions : OverlayActions {
    override fun onPause() = Unit
    override fun onResume() = Unit
    override fun onStop() = Unit
    override fun onExpand() = Unit
    override fun onCollapse() = Unit
}

/** 预览：运行中展开卡片。 */
@Preview(name = "运行中展开", showBackground = true, widthDp = 300)
@Composable
private fun OverlayRunningExpandedPreview() {
    AiAutoAndroidTheme {
        OverlayWindowContent(
            state = OverlaySessionState.Running(task = "登录并签到"),
            expanded = true,
            actions = NoopActions,
        )
    }
}

/** 预览：已暂停展开卡片。 */
@Preview(name = "已暂停展开", showBackground = true, widthDp = 300)
@Composable
private fun OverlayPausedExpandedPreview() {
    AiAutoAndroidTheme {
        OverlayWindowContent(
            state = OverlaySessionState.Paused(task = "数据同步"),
            expanded = true,
            actions = NoopActions,
        )
    }
}

/** 预览：完成态展开卡片。 */
@Preview(name = "完成展开", showBackground = true, widthDp = 300)
@Composable
private fun OverlayCompletedExpandedPreview() {
    AiAutoAndroidTheme {
        OverlayWindowContent(
            state = OverlaySessionState.Completed(task = "每日签到", summary = "签到成功，已获得积分"),
            expanded = true,
            actions = NoopActions,
        )
    }
}

/** 预览：失败态展开卡片。 */
@Preview(name = "失败展开", showBackground = true, widthDp = 300)
@Composable
private fun OverlayFailedExpandedPreview() {
    AiAutoAndroidTheme {
        OverlayWindowContent(
            state = OverlaySessionState.Failed(task = "自动下单", error = "未找到目标按钮"),
            expanded = true,
            actions = NoopActions,
        )
    }
}

/** 预览：收起态圆形图标（运行中）。 */
@Preview(name = "收起态", showBackground = true, widthDp = 60, heightDp = 60)
@Composable
private fun OverlayCollapsedPreview() {
    AiAutoAndroidTheme {
        OverlayWindowContent(
            state = OverlaySessionState.Running(task = "示例"),
            expanded = false,
            actions = NoopActions,
        )
    }
}
