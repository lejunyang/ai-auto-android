package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：将 LAN 配对 ViewModel 接入公共页面，并确保离开页面时停止短期网络会话。
 */

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import dev.aiauto.android.ui.components.ScreenScaffold

@Composable
fun LanPairingHost(
    viewModel: LanPairingViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        viewModel.updateScannerCapability(
            hardware = AndroidLanQrScanner.cameraHardware(context),
            providerAvailable = true,
        )
    }

    if (scanning) {
        EmbeddedLanQrScannerScreen(
            onResult = { result ->
                scanning = false
                viewModel.onQrScanResult(result)
            },
            onCancel = {
                scanning = false
                viewModel.onQrScanResult(LanQrScanResult.Cancelled)
            },
            modifier = modifier,
        )
        return
    }

    ScreenScaffold(
        title = "LAN 桌面连接",
        onBack = onBack,
    ) {
        LanPairingScreen(
            state = state.pairing,
            localInterfaces = state.localInterfaces,
            onLaunchScanner = { scanning = true },
            onManualInvitation = viewModel::submitManualInvitation,
            onSelectCandidate = viewModel::selectCandidate,
            onSelectLocalInterface = viewModel::selectLocalInterface,
            onConfirmFingerprintAndConnect = viewModel::confirmFingerprintAndConnect,
            onStop = viewModel::stop,
            modifier = modifier,
        )
    }
}
