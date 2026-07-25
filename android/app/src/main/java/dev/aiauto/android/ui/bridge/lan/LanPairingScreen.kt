package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：提供不绑定相机库或公共导航的 LAN 扫码配对页面，展示安全摘要和明确停止入口。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import dev.aiauto.android.bridge.lan.LanLocalInterface

@Composable
fun LanPairingScreen(
    state: LanPairingUiState,
    localInterfaces: List<LanLocalInterface>,
    onRequestCameraPermission: () -> Unit,
    onManualInvitation: (String) -> Unit,
    onSelectCandidate: (String, String) -> Unit,
    onSelectLocalInterface: (LanLocalInterface) -> Unit,
    onConfirmFingerprint: (String) -> Unit,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var manualInvitation by remember { mutableStateOf("") }
    var fingerprintInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("LAN 桌面连接", style = MaterialTheme.typography.headlineSmall)
        Text(scannerStatusText(state.scannerAvailability))
        if (state.scannerAvailability == ScannerAvailability.PERMISSION_REQUIRED) {
            OutlinedButton(onClick = onRequestCameraPermission) {
                Text("允许相机扫码")
            }
        }
        OutlinedTextField(
            value = manualInvitation,
            onValueChange = {
                manualInvitation = it.take(MAX_INVITATION_INPUT_CHARS)
            },
            label = { Text("手工邀请码") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val payload = manualInvitation
                manualInvitation = ""
                onManualInvitation(payload)
            },
            enabled = manualInvitation.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("校验手工邀请码")
        }
        state.invitation?.let { invitation ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("桌面网卡：${invitation.desktopInterfaceName}")
                    Text("类型：${invitation.desktopInterfaceKind}")
                    Text("桌面短指纹：${invitation.desktopFingerprint}")
                    Text("邀请过期：${invitation.expiresAt}")
                }
            }
            Text("选择桌面地址", style = MaterialTheme.typography.titleMedium)
            invitation.candidates.forEach { candidate ->
                SelectionRow(
                    selected = state.selectedCandidate == candidate,
                    label = "${candidate.host}:${candidate.port} (${candidate.family})",
                    onClick = { onSelectCandidate(candidate.host, candidate.interfaceId) },
                )
            }
            Text("选择手机网卡", style = MaterialTheme.typography.titleMedium)
            localInterfaces.forEach { localInterface ->
                SelectionRow(
                    selected = state.selectedLocalInterface == localInterface,
                    label = "${localInterface.name} (${localInterface.kind})",
                    onClick = { onSelectLocalInterface(localInterface) },
                )
            }
            OutlinedTextField(
                value = fingerprintInput,
                onValueChange = {
                    fingerprintInput = it.uppercase().take(MAX_FINGERPRINT_INPUT_CHARS)
                },
                label = { Text("再次输入桌面短指纹") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    val fingerprint = fingerprintInput
                    fingerprintInput = ""
                    onConfirmFingerprint(fingerprint)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("确认短指纹")
            }
        }
        state.localInterfaceName?.let { Text("当前手机网卡：$it") }
        state.sessionExpiresAt?.let { Text("会话过期：$it") }
        state.errorCode?.let {
            Text("连接已停止：$it", color = MaterialTheme.colorScheme.error)
        }
        if (state.canRequestConnection) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text("建立加密连接")
            }
        }
        if (state.stopAvailable) {
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("立即停止 LAN 会话")
            }
        }
    }
}

@Composable
private fun SelectionRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

private fun scannerStatusText(availability: ScannerAvailability): String = when (availability) {
    ScannerAvailability.READY -> "相机扫码可用，也可手工输入邀请码。"
    ScannerAvailability.PERMISSION_REQUIRED -> "扫码需要相机权限；不授权仍可手工输入。"
    ScannerAvailability.PERMISSION_DENIED -> "相机权限已拒绝，请改用手工邀请码。"
    ScannerAvailability.NO_CAMERA -> "设备无可用相机，请使用手工邀请码。"
    ScannerAvailability.PROVIDER_UNAVAILABLE -> "当前版本未接入受测扫码组件，请使用手工邀请码。"
}

private const val MAX_INVITATION_INPUT_CHARS = 64 * 1024
private const val MAX_FINGERPRINT_INPUT_CHARS = 19
