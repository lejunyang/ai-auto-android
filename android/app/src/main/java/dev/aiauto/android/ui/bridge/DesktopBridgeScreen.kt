package dev.aiauto.android.ui.bridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import dev.aiauto.android.bridge.BridgeLimits
import dev.aiauto.android.bridge.BridgeProtocol
import dev.aiauto.android.bridge.DesktopBridgeController
import dev.aiauto.android.bridge.DesktopBridgeStatus
import dev.aiauto.android.ui.components.ScreenScaffold

@Composable
fun DesktopBridgeScreen(
    controller: DesktopBridgeController,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = "桌面桥",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = when (state.status) {
                    DesktopBridgeStatus.STOPPED -> "桌面桥已关闭"
                    DesktopBridgeStatus.STARTING -> "正在启动本地桥"
                    DesktopBridgeStatus.WAITING_FOR_CODE -> "等待桌面配对"
                    DesktopBridgeStatus.CONNECTED -> "桌面会话已连接"
                    DesktopBridgeStatus.ERROR -> "桌面桥启动失败"
                },
                style = MaterialTheme.typography.headlineSmall,
                color = if (state.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "仅限本机 ADB 转发",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "服务只绑定 ${BridgeProtocol.LOOPBACK_ADDRESS}:" +
                            "${BridgeLimits.PORT}，不会监听 Wi-Fi 或移动网络。" +
                            "关闭后端口立即停止接受连接，桌面无法读取设备或界面数据。",
                    )
                    Text(
                        text = "一次性码有效 2 分钟且成功使用后立即失效；会话 token 最长有效 15 分钟，不会显示在界面或写入日志。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            state.pairingCode?.let { code ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("短期一次性码")
                        Text(
                            text = code,
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Text("请只提供给当前通过 ADB 授权的电脑。")
                    }
                }
            }
            if (
                state.status == DesktopBridgeStatus.WAITING_FOR_CODE &&
                state.pairingCode == null
            ) {
                Text(
                    text = "一次性码已过期或已使用，请生成新码后再连接。",
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            state.connectedHost?.let { host ->
                Text(
                    text = "当前电脑：$host",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!state.enabled) {
                Button(
                    onClick = controller::start,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("开启桌面桥")
                }
            } else {
                if (state.status != DesktopBridgeStatus.CONNECTED) {
                    OutlinedButton(
                        onClick = controller::rotatePairingCode,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("生成新的一次性码")
                    }
                }
                Button(
                    onClick = controller::stop,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("关闭桌面桥")
                }
            }
        }
    }
}
