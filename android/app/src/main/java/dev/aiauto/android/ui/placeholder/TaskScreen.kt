package dev.aiauto.android.ui.placeholder

/**
 * 功能用途：提供任务入口的占位页面并维持导航结构完整。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import dev.aiauto.android.ui.components.ScreenScaffold

@Composable
fun TaskScreen(onBack: () -> Unit) {
    ScreenScaffold(
        title = "新建 AI 任务",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "任务执行尚未启用",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "下一阶段将接入无障碍观察、动作风险确认和紧急停止。",
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RecordingScreen(onBack: () -> Unit) {
    ScreenScaffold(
        title = "录制操作",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "语义录制尚未启用",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "下一阶段将记录点击、文本、滚动与窗口变化，不会保存密码和验证码。",
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
