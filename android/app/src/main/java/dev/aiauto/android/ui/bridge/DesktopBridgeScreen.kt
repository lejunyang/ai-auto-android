package dev.aiauto.android.ui.bridge

/**
 * 功能用途：「连接电脑」页面。按原型 #connection 的信息层级重构表现层，
 * 承载真实的桌面桥能力：本机 ADB 转发开关、一次性配对码轮换、LAN 配对入口。
 *
 * 与原型的差异（有意为之，不照搬"演示"字样）：
 * - 原型是纯演示，按钮文案为「体验配对流程」；本页对接真实 DesktopBridgeController，
 *   因此按钮按真实状态显示「开启桌面桥 / 关闭桌面桥」，不伪装成演示。
 * - 原型底部「二维码、邀请码与连接均为原型演示」的免责声明不适用于真实功能，
 *   替换为真实的安全边界说明（仅绑定回环地址、码与 token 的有效期与不落盘）。
 *
 * 保留的安全文案（不可删）：仅本机回环绑定、不监听 Wi-Fi / 移动网络、
 * 一次性码 2 分钟且用后失效、会话 token 最长 15 分钟且不显示不写日志、
 * USB 调试信任与桌面桥是两层独立授权。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import dev.aiauto.android.R
import dev.aiauto.android.bridge.BridgeLimits
import dev.aiauto.android.bridge.BridgeProtocol
import dev.aiauto.android.bridge.DesktopBridgeController
import dev.aiauto.android.bridge.DesktopBridgeStatus
import dev.aiauto.android.ui.components.CenteredIconRow
import dev.aiauto.android.ui.components.NoteText
import dev.aiauto.android.ui.components.NoticeBar
import dev.aiauto.android.ui.components.PageDesc
import dev.aiauto.android.ui.components.PageHeading
import dev.aiauto.android.ui.components.ProtoCard
import dev.aiauto.android.ui.components.ProtoPrimaryButton
import dev.aiauto.android.ui.components.ProtoSecondaryButton
import dev.aiauto.android.ui.components.ProtoSectionHead
import dev.aiauto.android.ui.components.ProtoTextButton
import dev.aiauto.android.ui.components.ScreenScaffold
import dev.aiauto.android.ui.components.TagChip
import dev.aiauto.android.ui.components.TagTone
import dev.aiauto.android.ui.theme.AppColors
import dev.aiauto.android.ui.theme.AppSpacing

@Composable
fun DesktopBridgeScreen(
    controller: DesktopBridgeController,
    onBack: () -> Unit,
    onLanPairing: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = stringResource(R.string.bridge_page_title),
        onBack = onBack,
        subtitle = stringResource(R.string.bridge_page_subtitle),
        infoText = stringResource(R.string.bridge_page_info),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.ScreenHorizontal)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. 页面大标题与描述（原型 page-heading + page-desc）。
            PageHeading(text = stringResource(R.string.bridge_heading))
            PageDesc(text = stringResource(R.string.bridge_desc))

            Spacer(Modifier.height(4.dp))

            // 2. 主操作卡片：居中「手机 → 电脑」图标行 + 当前状态 + 主按钮。
            //    原型是居中布局（text-align:center，padding 28x18），此处保持一致。
            ProtoCard(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 18.dp,
                    vertical = 28.dp,
                ),
            ) {
                CenteredIconRow(
                    icons = listOf(
                        Icons.Default.PhoneAndroid,
                        Icons.AutoMirrored.Filled.ArrowForward,
                        Icons.Default.Computer,
                    ),
                )
                Spacer(Modifier.height(16.dp))
                // 状态标题来自真实 controller 状态，不硬编码。
                Text(
                    text = bridgeStatusTitle(state.status),
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 17.sp,
                    lineHeight = 25.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.Ink,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(7.dp))
                NoteText(
                    text = bridgeStatusHint(state.status),
                    textAlign = TextAlign.Center,
                )

                // 一次性配对码：仅在存在时展示，属真实运行态数据。
                state.pairingCode?.let { code ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = code,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.Primary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    NoteText(
                        text = stringResource(R.string.bridge_code_scope),
                        textAlign = TextAlign.Center,
                    )
                }

                // 一次性码已失效的真实反馈。
                if (
                    state.status == DesktopBridgeStatus.WAITING_FOR_CODE &&
                    state.pairingCode == null
                ) {
                    Spacer(Modifier.height(12.dp))
                    NoteText(
                        text = stringResource(R.string.bridge_code_expired),
                        textAlign = TextAlign.Center,
                    )
                }

                // 已连接的电脑标识（真实会话信息）。
                state.connectedHost?.let { host ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.bridge_connected_host, host),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.Primary,
                        textAlign = TextAlign.Center,
                    )
                }

                // 启动失败等错误的真实反馈。
                state.errorMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.Danger,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(20.dp))

                // 主按钮：按真实开关状态切换，不使用原型的"体验"字样。
                if (!state.enabled) {
                    ProtoPrimaryButton(
                        text = stringResource(R.string.bridge_start),
                        onClick = controller::start,
                    )
                } else {
                    ProtoPrimaryButton(
                        text = stringResource(R.string.bridge_stop),
                        onClick = controller::stop,
                    )
                    if (state.status != DesktopBridgeStatus.CONNECTED) {
                        Spacer(Modifier.height(8.dp))
                        ProtoSecondaryButton(
                            text = stringResource(R.string.bridge_rotate_code),
                            onClick = controller::rotatePairingCode,
                        )
                    }
                }

                // 文字按钮：LAN 配对入口（原型对应「输入邀请码演示」的位置，居中）。
                ProtoTextButton(
                    text = stringResource(R.string.bridge_open_lan),
                    onClick = onLanPairing,
                    modifier = Modifier.fillMaxWidth(),
                    center = true,
                )
            }

            // 3.「也可以用 USB」分区，带「已有基础」标签（原型 section-head + tag）。
            ProtoSectionHead(title = stringResource(R.string.bridge_usb_section)) {
                TagChip(
                    text = stringResource(R.string.bridge_usb_tag),
                    tone = TagTone.NEUTRAL,
                )
            }
            NoteText(text = stringResource(R.string.bridge_usb_note))

            Spacer(Modifier.height(8.dp))

            // 4. 底部安全边界提示条：真实约束，不是原型免责声明。
            NoticeBar(text = stringResource(R.string.bridge_security_notice, BridgeProtocol.LOOPBACK_ADDRESS, BridgeLimits.PORT))
        }
    }
}

/** 把真实桥状态映射为卡片主标题，不硬编码就绪。 */
@Composable
private fun bridgeStatusTitle(status: DesktopBridgeStatus): String = stringResource(
    when (status) {
        DesktopBridgeStatus.STOPPED -> R.string.bridge_status_stopped
        DesktopBridgeStatus.STARTING -> R.string.bridge_status_starting
        DesktopBridgeStatus.WAITING_FOR_CODE -> R.string.bridge_status_waiting
        DesktopBridgeStatus.CONNECTED -> R.string.bridge_status_connected
        DesktopBridgeStatus.ERROR -> R.string.bridge_status_error
    },
)

/** 状态对应的一句话说明，帮助用户判断下一步。 */
@Composable
private fun bridgeStatusHint(status: DesktopBridgeStatus): String = stringResource(
    when (status) {
        DesktopBridgeStatus.STOPPED -> R.string.bridge_hint_stopped
        DesktopBridgeStatus.STARTING -> R.string.bridge_hint_starting
        DesktopBridgeStatus.WAITING_FOR_CODE -> R.string.bridge_hint_waiting
        DesktopBridgeStatus.CONNECTED -> R.string.bridge_hint_connected
        DesktopBridgeStatus.ERROR -> R.string.bridge_hint_error
    },
)
