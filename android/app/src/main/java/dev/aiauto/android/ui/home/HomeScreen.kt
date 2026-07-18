package dev.aiauto.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import dev.aiauto.android.ui.components.StatusCard
import dev.aiauto.android.ui.components.StatusTone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    providerReady: Boolean,
    accessibilityConfigured: Boolean,
    accessibilityEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onProviderClick: () -> Unit,
    onTaskClick: () -> Unit,
    onRecordingClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI 安卓自动化")
                        Text(
                            text = "本地优先、明确授权、随时停止",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    text = "运行状态",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            item {
                StatusCard(
                    title = "无障碍执行",
                    description = when {
                        accessibilityEnabled && accessibilityConfigured ->
                            "仅观察和操作你明确配置的目标应用。"

                        accessibilityConfigured ->
                            "披露与目标应用已配置，请到系统设置手动启用服务。"

                        else ->
                            "查看读取与操作范围，选择目标应用后再前往系统设置授权。"
                    },
                    status = when {
                        accessibilityEnabled && accessibilityConfigured -> "已启用"
                        accessibilityConfigured -> "系统未启用"
                        else -> "需披露"
                    },
                    tone = if (accessibilityEnabled && accessibilityConfigured) {
                        StatusTone.READY
                    } else {
                        StatusTone.ATTENTION
                    },
                    onClick = onAccessibilityClick,
                )
            }
            item {
                StatusCard(
                    title = "AI Provider",
                    description = if (providerReady) {
                        "API Key 已加密保存，可以测试 Provider 连接。"
                    } else {
                        "配置 OpenAI 兼容地址、模型与 API Key。"
                    },
                    status = if (providerReady) "已配置" else "未配置",
                    tone = if (providerReady) StatusTone.READY else StatusTone.ATTENTION,
                    onClick = onProviderClick,
                )
            }
            item {
                StatusCard(
                    title = "桌面桥",
                    description = "默认关闭。连接时仅通过 ADB 转发访问设备回环地址。",
                    status = "已关闭",
                    tone = StatusTone.INACTIVE,
                )
            }
            item {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "开始使用",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "先完成 Provider 与无障碍配置，再运行任务或录制固定流程。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onTaskClick,
                        enabled = providerReady && accessibilityEnabled && accessibilityConfigured,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("新建 AI 任务")
                    }
                    OutlinedButton(
                        onClick = onRecordingClick,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("录制操作")
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "最近会话",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "还没有运行记录。会话审计只保存动作摘要，不记录密码、验证码或原始截图。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
