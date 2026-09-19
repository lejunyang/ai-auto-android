package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：全屏展示内置扫码预览，相机预览铺满整屏、左上角悬浮返回图标；仅解析首个有效 QR
 * 结果并回传给配对页，不再使用底部白色 footer。
 */

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.aiauto.android.ui.components.AppIcons
import dev.aiauto.android.ui.components.protoClickable
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (permissionGranted) {
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

            // 预览铺满整屏，不再保留底部按钮区。
            AndroidView(
                factory = { current ->
                    PreviewView(current).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = embeddedLanQrPreviewMode()
                        this.controller = controller
                        contentDescription = "内置 LAN 二维码扫描预览"
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 未授权：全屏黑底居中简短提示，左上角同样可返回。
            Text(
                text = "等待相机权限…",
                color = Color(0xFFB7BEC9),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 左上悬浮返回图标：半透明黑圆底 + 白色箭头，避开状态栏。
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 12.dp, top = 8.dp)
                .size(40.dp)
                .background(Color(0x66000000), CircleShape)
                .protoClickable(onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.ArrowLeft,
                contentDescription = "取消扫码",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

internal fun embeddedLanQrPreviewMode(): PreviewView.ImplementationMode =
    PreviewView.ImplementationMode.COMPATIBLE
