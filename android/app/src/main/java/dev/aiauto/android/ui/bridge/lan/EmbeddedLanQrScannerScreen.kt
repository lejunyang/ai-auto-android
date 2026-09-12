package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：提供用户明确授权相机后的内置扫码预览，并仅把首个有效 QR 结果返回配对页。
 */

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

@Composable
fun EmbeddedLanQrScannerScreen(
    onResult: (LanQrScanResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (!granted) onResult(LanQrScanResult.PermissionDenied)
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!permissionGranted) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("等待相机权限；拒绝后仍可返回使用手工邀请码。")
            Button(onClick = onCancel) {
                Text("取消扫码")
            }
        }
        return
    }

    val mainExecutor = remember(context) {
        ContextCompat.getMainExecutor(context)
    }
    val analyzer = remember {
        EmbeddedLanQrAnalyzer(EmbeddedLanQrDecoder()) { result ->
            mainExecutor.execute {
                onResult(result)
            }
        }
    }
    val analyzerExecutor = remember {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "embedded-lan-qr").apply { isDaemon = true }
        }
    }
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(LifecycleCameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            setImageAnalysisAnalyzer(analyzerExecutor, analyzer)
        }
    }
    DisposableEffect(controller, lifecycleOwner, analyzer, analyzerExecutor) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            analyzer.close()
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analyzerExecutor.shutdownNow()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { current ->
                PreviewView(current).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = embeddedLanQrPreviewMode()
                    this.controller = controller
                    contentDescription = "内置 LAN 二维码扫描预览"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Button(
            onClick = onCancel,
            modifier = Modifier.padding(16.dp),
        ) {
            Text("取消扫码")
        }
    }
}

internal fun embeddedLanQrPreviewMode(): PreviewView.ImplementationMode =
    PreviewView.ImplementationMode.COMPATIBLE
