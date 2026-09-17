package dev.aiauto.android.ui.components

/**
 * 功能用途：提供 C 端四 tab 架构下的共享 Compose 组件，包括底部导航、段落标题、
 * 技能卡片、标签、空态与问候头部，统一各主页面的视觉语言与交互模式。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import dev.aiauto.android.ui.theme.AppColors
import dev.aiauto.android.ui.theme.AppSpacing
import dev.aiauto.android.ui.theme.AppTypographyTokens

/** 底部导航的四个主入口定义，路由常量与 AiAutoAndroidApp 中的 Route 保持一致。 */
enum class AppTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    ASSISTANT("assistant", "助理", AppIcons.Spark),
    SKILLS("skills", "技能", AppIcons.Flow),
    HISTORY("history", "记录", AppIcons.Clock),
    SETTINGS("settings", "我的", AppIcons.Settings),
    ;

    companion object {
        /** 根据路由查找所属 tab，子页面返回 null。 */
        fun fromRoute(route: String?): AppTab? =
            entries.firstOrNull { it.route == route }
    }
}

@Composable
fun AppBottomNavigation(
    currentRoute: String?,
    onTabSelected: (AppTab) -> Unit,
) {
    // 原型底栏精确值：内容区68dp（手机模式媒体查询未覆盖height，沿用桌面68px）、
    // padding 7dp/8dp/4dp、顶部1dp #E5EAF1描边；
    // tab按钮 Column居中、图标与文字gap 3dp、min-height 48dp；
    // 选中态胶囊 46dp宽×27dp高、圆角12dp、背景#E8EFFD；图标20dp、标签12sp。
    // 根容器加 navigationBarsPadding 处理系统手势条，避免手势条压标签。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.SurfaceMuted)
            .navigationBarsPadding()
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRect(
                    color = AppColors.Line,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, stroke),
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppSpacing.NavHeight)
                .padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 4.dp),
        ) {
            AppTab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clickable { onTabSelected(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    // 选中态胶囊：46dp宽×27dp高、圆角12dp、背景#E8EFFD；未选中透明。
                    Box(
                        modifier = Modifier
                            .width(46.dp)
                            .height(27.dp)
                            .background(
                                color = if (selected) AppColors.NavActiveBackground else Color.Transparent,
                                shape = RoundedCornerShape(12.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(20.dp),
                            tint = if (selected) AppColors.Primary else AppColors.NavInactive,
                        )
                    }
                    Text(
                        text = tab.label,
                        fontSize = 12.sp,
                        color = if (selected) AppColors.Primary else AppColors.NavInactive,
                    )
                }
            }
        }
    }
}

/** 段落标题，右侧可放置操作按钮（如"查看全部"）。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
        if (actionLabel != null && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 技能/记录卡片，展示图标、名称、描述与元数据。 */
@Composable
fun SkillCard(
    title: String,
    description: String,
    meta: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
) {
    Card(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (icon != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) { icon() }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** 标签 chip，用于状态标记（如"已完成""需处理""示例"）。 */
@Composable
fun TagChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: TagTone = TagTone.NEUTRAL,
) {
    val colors = when (tone) {
        TagTone.SUCCESS -> TagColors(
            background = MaterialTheme.colorScheme.primaryContainer,
            foreground = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        TagTone.WARNING -> TagColors(
            // 原型琥珀色：--amber:#9b6419，用于"未完成配置""需处理"等警告状态。
            background = Color(0xFFFBF0DA),
            foreground = Color(0xFF9B6419),
        )

        TagTone.ERROR -> TagColors(
            background = MaterialTheme.colorScheme.errorContainer,
            foreground = MaterialTheme.colorScheme.onErrorContainer,
        )

        TagTone.NEUTRAL -> TagColors(
            background = MaterialTheme.colorScheme.surfaceVariant,
            foreground = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.background,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = colors.foreground,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

enum class TagTone { SUCCESS, WARNING, ERROR, NEUTRAL }

private data class TagColors(
    val background: androidx.compose.ui.graphics.Color,
    val foreground: androidx.compose.ui.graphics.Color,
)

/** 空态提示，用于无脚本、无记录等场景。 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** 助理页问候头部，展示欢迎语与应用标识。 */
@Composable
fun GreetingHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

/** 就绪状态徽章，展示在助理页头部右侧。 */
@Composable
fun ReadyBadge(
    ready: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (ready) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = if (ready) "已就绪" else "待配置",
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

/** 横向分隔线。 */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}
