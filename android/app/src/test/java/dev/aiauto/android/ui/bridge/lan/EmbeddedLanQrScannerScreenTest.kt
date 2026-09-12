package dev.aiauto.android.ui.bridge.lan

/**
 * 测试用途：回归内置扫码必须同时绑定 CameraX 预览与分析流，防止真机只分析却显示白屏。
 */

import androidx.camera.view.PreviewView
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedLanQrScannerScreenTest {
    @Test
    fun `scanner forces compatible preview for vendor surface reliability`() {
        assertEquals(
            PreviewView.ImplementationMode.COMPATIBLE,
            embeddedLanQrPreviewMode(),
        )
    }
}
