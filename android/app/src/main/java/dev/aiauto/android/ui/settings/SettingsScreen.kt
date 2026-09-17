package dev.aiauto.android.ui.settings

/**
 * 功能用途：C 端"我的"主页面。聚合助理就绪状态与三个配置入口
 * （AI 模型、允许操作的应用、连接电脑），所有状态由导航层注入，本页不硬编码任何状态。
 */

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

import dev.aiauto.android.R
import dev.aiauto.android.bridge.DesktopBridgeState
import dev.aiauto.android.bridge.DesktopBridgeStatus
import dev.aiauto.android.ui.components.Divider
import dev.aiauto.android.ui.components.SectionHeader
import dev.aiauto.android.ui.components.TagChip
import dev.aiauto.android.ui.components.TagTone
import dev.aiauto.android.ui.theme.AppColors
import dev.aiauto.android.ui.theme.AppShapes
import dev.aiauto.android.ui.theme.AppSpacing

/**
 * "我的"主页面。
 *
 * @param providerReady Provider 是否已配置 API Key。
 * @param accessibilityConfigured 无障碍披露已接受且目标应用非空。
 * @param accessibilityEnabled 系统无障碍服务是否已启用。
 * @param bridgeState 桌面桥连接状态，含 status 与 errorMessage。
 * @param targetPackages 已配置的目标应用包名集合。
 * @param onProviderClick 点击"AI 模型"入口。
 * @param onAccessibilityClick 点击"允许操作的应用"入口。
 * @param onBridgeClick 点击"连接电脑"入口。
 */
@Composable
fun SettingsScreen(
    providerReady: Boolean,
    accessibilityConfigured: Boolean,
    accessibilityEnabled: Boolean,
    bridgeState: DesktopBridgeState,
    targetPackages: Set<String>,
    onProviderClick: () -> Unit,
    onAccessibilityClick: () -> Unit,
    onBridgeClick: () -> Unit,
    onOverlayClick: () -> Unit,
) {
    // 就绪条件：模型已配置、无障碍已披露且系统服务已启用；桥接状态不参与就绪判断。
    val ready = providerReady && accessibilityEnabled && accessibilityConfigured

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 0. 页头：原型 setHeader('我的','把边界设好，其余交给它。') + 右上 info 按钮。
        item {
            SettingsPageHeader()
        }

        // 1. 个人信息头部：左侧 logo，右侧名称与本地优先定位。
        item {
            ProfileHeader()
        }

        // 2. 助理状态卡片：总览就绪 TagChip，并逐项展示模型与无障碍子状态。
        item {
            AssistantStatusCard(
                ready = ready,
                providerReady = providerReady,
                accessibilityEnabled = accessibilityEnabled,
            )
        }

        // 3. 连接与能力：段落标题。
        item {
            SectionHeader(title = stringResource(R.string.settings_connections))
        }

        // 3. 连接与能力：三个可点击设置入口，放入同一卡片以保持视觉聚合。
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column {
                    // AI 模型入口：状态随 providerReady 切换。
                    SettingRow(
                        icon = Icons.Default.AutoAwesome,
                        title = stringResource(R.string.settings_provider_title),
                        description = stringResource(R.string.settings_provider_desc),
                        status = stringResource(
                            if (providerReady) R.string.settings_provider_configured
                            else R.string.settings_provider_not_configured,
                        ),
                        onClick = onProviderClick,
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))

                    // 允许操作的应用入口：状态随服务启用切换，并展示目标应用数量。
                    SettingRow(
                        icon = Icons.Default.Security,
                        title = stringResource(R.string.settings_accessibility_title),
                        description = stringResource(R.string.settings_accessibility_desc),
                        status = stringResource(
                            if (accessibilityEnabled) R.string.settings_accessibility_enabled
                            else R.string.settings_accessibility_not_enabled,
                        ),
                        extra = stringResource(
                            R.string.settings_target_count,
                            targetPackages.size,
                        ),
                        onClick = onAccessibilityClick,
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))

                    // 连接电脑入口：仅在 CONNECTED 时显示已连接，其余均为未连接。
                    val bridgeConnected = bridgeState.status == DesktopBridgeStatus.CONNECTED
                    SettingRow(
                        icon = Icons.Default.Computer,
                        title = stringResource(R.string.settings_bridge_title),
                        description = stringResource(R.string.settings_bridge_desc),
                        status = stringResource(
                            if (bridgeConnected) R.string.settings_bridge_connected
                            else R.string.settings_bridge_disconnected,
                        ),
                        onClick = onBridgeClick,
                    )
                }
            }
        }

        // 4. 随时唤起：悬浮入口与运行通知，会话运行期间可在其他应用旁观察和控制。
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(title = stringResource(R.string.settings_quick_access_title))
                TagChip(
                    text = stringResource(R.string.settings_quick_access_tag),
                    tone = TagTone.NEUTRAL,
                )
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column {
                    // 悬浮入口：需系统 SYSTEM_ALERT_WINDOW 权限，未授权时引导至系统设置。
                    SettingRow(
                        icon = Icons.Default.Layers,
                        title = stringResource(R.string.settings_overlay_title),
                        description = stringResource(R.string.settings_overlay_desc),
                        status = stringResource(R.string.settings_overlay_permission_needed),
                        onClick = onOverlayClick,
                    )
                    // 运行通知：状态来自真实通知权限与渠道，不硬编码"已启用"。
                    val context = LocalContext.current
                    val notificationEnabled = remember(context) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                    }
                    SettingRow(
                        icon = Icons.Default.Notifications,
                        title = stringResource(R.string.settings_notification_title),
                        description = stringResource(R.string.settings_notification_desc),
                        status = if (notificationEnabled) "可用" else "需授权",
                        onClick = {},
                    )
                }
            }
        }

        // 5. 控制权说明：段落标题与说明文字。
        item {
            SectionHeader(title = stringResource(R.string.settings_control_title))
        }
        item {
            Text(
                text = stringResource(R.string.settings_control_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 我的页页头。对应原型 setHeader('我的','把边界设好，其余交给它。')：
 * 左侧标题 + 副标题，右侧 info 按钮（说明本页边界，不改任何系统设置）。
 */
@Composable
private fun SettingsPageHeader(
    modifier: Modifier = Modifier,
) {
    var showInfo by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.HeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_page_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = AppColors.Ink,
            )
            Text(
                text = stringResource(R.string.settings_page_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Muted,
            )
        }
        IconButton(
            onClick = { showInfo = true },
            modifier = Modifier.size(AppSpacing.IconButtonSize),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.settings_page_info),
                tint = AppColors.Muted,
                modifier = Modifier.size(22.dp),
            )
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(stringResource(R.string.settings_page_info)) },
            text = { Text(stringResource(R.string.settings_page_info_body)) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(R.string.common_got_it))
                }
            },
        )
    }
}

/** 个人信息头部：52dp 圆角方形 logo + 名称与"本地优先 · 自己掌控"定位语。 */
@Composable
private fun ProfileHeader(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            // 原型 logo 是蓝色实心圆角方块（bg #2563EB，radius 18dp，52x52），
            // 不是浅蓝底 primaryContainer。
            shape = RoundedCornerShape(AppShapes.SettingsLogo),
            color = AppColors.Primary,
            modifier = Modifier.size(AppSpacing.SettingsLogoSize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = AppColors.Surface,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = stringResource(R.string.settings_profile_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.settings_profile_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 助理状态卡片：标题行右侧 TagChip 标注就绪/未完成，下方逐项列出子状态。 */
@Composable
private fun AssistantStatusCard(
    ready: Boolean,
    providerReady: Boolean,
    accessibilityEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 标题行：左侧"助理状态"，右侧就绪标签。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_status_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                TagChip(
                    text = stringResource(
                        if (ready) R.string.settings_status_ready
                        else R.string.settings_status_not_ready,
                    ),
                    tone = if (ready) TagTone.SUCCESS else TagTone.WARNING,
                )
            }
            // 逐项说明模型与无障碍子状态，便于一眼定位未完成项。
            StatusLine(
                label = stringResource(R.string.settings_provider_title),
                value = stringResource(
                    if (providerReady) R.string.settings_provider_configured
                    else R.string.settings_provider_not_configured,
                ),
            )
            StatusLine(
                label = stringResource(R.string.settings_accessibility_title),
                value = stringResource(
                    if (accessibilityEnabled) R.string.settings_accessibility_enabled
                    else R.string.settings_accessibility_not_enabled,
                ),
            )
        }
    }
}

/** 状态卡片内的一行说明：左侧子项名称，右侧当前状态。 */
@Composable
private fun StatusLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 设置行：图标 + 标题/描述/附加信息 + 右侧状态文字与箭头，整行可点击。 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    description: String,
    status: String,
    onClick: () -> Unit,
    extra: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧图标底。
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        // 中间：标题、描述与可选附加信息（如目标应用数量）。
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (extra != null) {
                Text(
                    text = extra,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // 右侧状态文字与右向箭头。
        Text(
            text = status,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
