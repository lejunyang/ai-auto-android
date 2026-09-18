package dev.aiauto.android.ui.components

/**
 * 功能用途：二级页面通用外壳。对应原型 setHeader(title, subtitle, showBack=true)：
 * 返回箭头 + 标题 + 副标题 + 右上 info 按钮，背景与卡片语言与主 tab 一致。
 *
 * 与旧实现的差异（修复用户指出的"二级页仍是旧界面"）：
 * - 旧版用 Material3 TopAppBar + 硬编码"返回"文字按钮，无副标题、无 info；
 *   新版按原型用返回箭头图标 + 标题/副标题两行 + 可选 info 按钮。
 * - 背景使用 AppColors.Background（原型 --bg #EEF1F5），不用 TopAppBar 默认表面色。
 *
 * 表现层重做，不改任何页面的功能与安全文案。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import dev.aiauto.android.ui.components.ProtoIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import dev.aiauto.android.ui.components.ProtoTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import dev.aiauto.android.R
import dev.aiauto.android.ui.theme.AppColors
import dev.aiauto.android.ui.theme.AppSpacing

/**
 * 二级页面外壳。
 *
 * @param title 页面标题，对应原型 setHeader 第一参数。
 * @param onBack 返回回调；返回入口在所有二级页保留。
 * @param subtitle 页面副标题，对应原型 setHeader 第二参数；为空时不渲染。
 * @param infoText 右上 info 按钮点击后展示的说明；为空时不渲染 info 按钮。
 * @param content 页面主体，接收一个已应用水平内边距的 Modifier。
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    infoText: String? = null,
    content: @Composable ColumnScope.(Modifier) -> Unit,
) {
    Scaffold(
        containerColor = AppColors.Background,
        topBar = {
            ScreenHeader(
                title = title,
                subtitle = subtitle,
                infoText = infoText,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clearFocusOnTapOutside(),
        ) {
            content(Modifier)
        }
    }
}

/**
 * 二级页页头：返回箭头 + 标题/副标题 + 可选 info。
 * 高度与主 tab 页头一致（原型实测 60dp），左右内边距 20dp。
 */
@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String?,
    infoText: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showInfo by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.HeaderHeight)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        ProtoIconButton(
            onClick = onBack,
            modifier = Modifier.size(AppSpacing.IconButtonSize),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = AppColors.Ink,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AppColors.Ink,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted,
                )
            }
        }
        if (!infoText.isNullOrBlank()) {
            ProtoIconButton(
                onClick = { showInfo = true },
                modifier = Modifier.size(AppSpacing.IconButtonSize),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.common_info),
                    tint = AppColors.Muted,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    if (showInfo && !infoText.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(title) },
            text = { Text(infoText) },
            confirmButton = {
                ProtoTextButton(text = stringResource(R.string.common_got_it), onClick = { showInfo = false })
            },
        )
    }
}

/** 二级页统一内容内边距：左右 20dp（原型 screenPadding），底部留出滚动余量。 */
val ScreenContentPadding: PaddingValues =
    PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp)
