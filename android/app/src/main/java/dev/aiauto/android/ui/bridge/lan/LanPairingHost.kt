package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：将 LAN 配对 ViewModel 接入公共页面，并确保离开页面时停止短期网络会话。
 */

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val scanner = remember(context) { AndroidLanQrScanner.discover(context) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onQrScanResult(
            scanner?.parseResult(result) ?: LanQrScanResult.ProviderFailed,
        )
    }
    LaunchedEffect(context, scanner) {
        viewModel.updateScannerCapability(
            hardware = AndroidLanQrScanner.cameraHardware(context),
            providerAvailable = scanner != null,
        )
    }

    ScreenScaffold(
        title = "LAN 桌面连接",
        onBack = onBack,
    ) {
        LanPairingScreen(
            state = state.pairing,
            localInterfaces = state.localInterfaces,
            onLaunchScanner = {
                val launchResult = runCatching {
                    launcher.launch(requireNotNull(scanner).createIntent())
                }
                if (launchResult.isFailure) {
                    viewModel.onQrScanResult(LanQrScanResult.ProviderFailed)
                }
            },
            onManualInvitation = viewModel::submitManualInvitation,
            onSelectCandidate = viewModel::selectCandidate,
            onSelectLocalInterface = viewModel::selectLocalInterface,
            onConfirmFingerprint = viewModel::confirmFingerprint,
            onConnect = viewModel::connect,
            onStop = viewModel::stop,
            modifier = modifier,
        )
    }
}
