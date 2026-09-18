package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：LAN 手工邀请码配对页面。按原型 pair-manual ModalBottomSheet 的设计语言
 * 重构表现层，保留真实邀请解析、候选地址选择、网卡选择、指纹确认与停止能力。
 *
 * 与原型的差异（有意为之）：
 * - 原型是纯演示弹窗，只接受 DEMO；本页对接真实 LanPairingViewModel，
 *   接受真实 LAN 邀请码并驱动真实状态机。
 * - 原型缺扫码按钮，此处补一个扫码入口；点击时给出"扫码能力规划中"的诚实反馈，
 *   不假称已实现。onLaunchScanner 回调仍保留在签名中，供未来接线。
 * - 全部用户可见文本走 stringResource，不硬编码中文。
 */

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.SolidColor

import dev.aiauto.android.R
import dev.aiauto.android.bridge.lan.LanLocalInterface
import dev.aiauto.android.ui.components.FieldLabel
import dev.aiauto.android.ui.components.NoteText
import dev.aiauto.android.ui.components.PageDesc
import dev.aiauto.android.ui.components.PageHeading
import dev.aiauto.android.ui.components.ProtoCard
import dev.aiauto.android.ui.components.ProtoPrimaryButton
import dev.aiauto.android.ui.components.ProtoSectionHead
import dev.aiauto.android.ui.components.ProtoSecondaryButton
import dev.aiauto.android.ui.theme.AppColors
import dev.aiauto.android.ui.theme.AppSpacing
import dev.aiauto.android.ui.theme.AppTypographyTokens

@Composable
fun LanPairingScreen(
    state: LanPairingUiState,
    localInterfaces: List<LanLocalInterface>,
    onLaunchScanner: () -> Unit,
    onManualInvitation: (String) -> Unit,
    onSelectCandidate: (String, String) -> Unit,
    onSelectLocalInterface: (LanLocalInterface) -> Unit,
    onConfirmFingerprintAndConnect: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var manualInvitation by remember { mutableStateOf("") }
    // 扫码入口的诚实反馈：点击后展示"规划中"提示，不启动假扫码流程。
    var showScanPlanning by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.ScreenHorizontal)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. 页面大标题与描述（对齐原型 page-heading + page-desc）。
        PageHeading(text = stringResource(R.string.lan_heading))
        PageDesc(text = stringResource(R.string.lan_desc))

        Spacer(Modifier.height(4.dp))

        // 2. 扫码入口按钮：原型缺此入口，补充后给诚实反馈。
        ProtoSecondaryButton(
            text = stringResource(R.string.lan_scan_entry),
            onClick = { showScanPlanning = true },
        )
        if (showScanPlanning) {
            NoteText(text = stringResource(R.string.lan_scan_planning))
        }

        // 3. 手工邀请码输入（对齐原型 pair-manual 弹窗的 label + input）。
        FieldLabel(text = stringResource(R.string.lan_manual_label))
        ProtoInvitationTextField(
            value = manualInvitation,
            onValueChange = {
                manualInvitation = it.take(MAX_MANUAL_INVITATION_INPUT_CHARS)
                showScanPlanning = false
            },
            placeholder = stringResource(R.string.lan_manual_placeholder),
        )
        Spacer(Modifier.height(8.dp))
        ProtoPrimaryButton(
            text = stringResource(R.string.lan_manual_submit),
            onClick = {
                val payload = manualInvitation
                manualInvitation = ""
                onManualInvitation(payload)
            },
            enabled = manualInvitation.isNotBlank(),
        )

        // 4. 邀请解析后的核对信息卡（对齐原型指纹示意卡）。
        state.invitation?.let { invitation ->
            Spacer(Modifier.height(8.dp))
            ProtoSectionHead(title = stringResource(R.string.lan_review_title))
            ProtoCard {
                InvitationRow(
                    label = stringResource(R.string.lan_review_desktop),
                    value = invitation.desktopInterfaceName,
                )
                InvitationRow(
                    label = stringResource(R.string.lan_review_desktop_type),
                    value = invitation.desktopInterfaceKind,
                )
                // 短指纹大字展示，与原型指纹示意一致。
                Column(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.lan_review_fingerprint),
                        fontSize = AppTypographyTokens.TileMetaFontSize,
                        lineHeight = AppTypographyTokens.TileMetaLineHeight,
                        color = AppColors.Muted,
                    )
                    Text(
                        text = invitation.desktopFingerprint,
                        fontSize = 23.sp,
                        lineHeight = 30.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.Ink,
                    )
                }
                InvitationRow(
                    label = stringResource(R.string.lan_review_expires),
                    value = invitation.expiresAt.toString(),
                )
            }

            // 桌面地址候选列表。
            ProtoSectionHead(title = stringResource(R.string.lan_select_host))
            invitation.candidates.forEach { candidate ->
                SelectionRow(
                    selected = state.selectedCandidate == candidate,
                    label = "${candidate.host}:${candidate.port} (${candidate.family})",
                    onClick = { onSelectCandidate(candidate.host, candidate.interfaceId) },
                )
            }

            // 手机网卡列表。
            ProtoSectionHead(title = stringResource(R.string.lan_select_iface))
            localInterfaces.forEach { localInterface ->
                SelectionRow(
                    selected = state.selectedLocalInterface == localInterface,
                    label = "${localInterface.name} (${localInterface.kind})",
                    onClick = { onSelectLocalInterface(localInterface) },
                )
            }

            // 指纹确认后才能连接。
            if (
                state.selectedCandidate != null &&
                state.selectedLocalInterface != null
            ) {
                Spacer(Modifier.height(4.dp))
                ProtoPrimaryButton(
                    text = stringResource(R.string.lan_confirm_connect),
                    onClick = onConfirmFingerprintAndConnect,
                )
            }
        }

        // 5. 运行态信息：当前网卡、会话过期、错误码。
        state.localInterfaceName?.let { name ->
            NoteText(text = stringResource(R.string.lan_current_iface, name))
        }
        state.sessionExpiresAt?.let { expires ->
            NoteText(text = stringResource(R.string.lan_session_expires, expires.toString()))
        }
        state.errorCode?.let { code ->
            NoteText(text = stringResource(R.string.lan_error_prefix, code))
        }

        // 6. 停止按钮：连接中或已连接时可用。
        if (state.stopAvailable) {
            Spacer(Modifier.height(8.dp))
            ProtoPrimaryButton(
                text = stringResource(R.string.lan_stop),
                onClick = onStop,
            )
        }
    }
}

/** 邀请信息行：左标签（次要色）+ 右值（主色）。 */
@Composable
private fun InvitationRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = AppTypographyTokens.TileMetaFontSize,
            lineHeight = AppTypographyTokens.TileMetaLineHeight,
            color = AppColors.Muted,
        )
        Text(
            text = value,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.Ink,
        )
    }
}

/** 候选/网卡选择行：RadioButton + 标签文字，整行可点击选择。 */
@Composable
private fun SelectionRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    // 整行无波纹点击，避免 RadioButton 自带涟漪与行点击重叠。
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.MinTouchTarget)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = AppColors.Primary,
                unselectedColor = AppColors.Muted,
            ),
        )
        Text(
            text = label,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = AppColors.Ink,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 邀请码输入框：与 ProviderScreen 的 ProtoTextField 同风格，白底细描边圆角。 */
@Composable
private fun ProtoInvitationTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val shape = RoundedCornerShape(13.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        visualTransformation = VisualTransformation.None,
        cursorBrush = SolidColor(AppColors.Primary),
        textStyle = TextStyle(
            color = AppColors.Ink,
            fontSize = 16.sp,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(AppColors.Surface, shape)
            .border(BorderStroke(1.dp, AppColors.Line), shape),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TextStyle(
                                color = AppColors.ComposerPlaceholder,
                                fontSize = 16.sp,
                            ),
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

private const val MAX_MANUAL_INVITATION_INPUT_CHARS =
    "AIAUTO1-".length + (64 * 1024 * 8 + 4) / 5
