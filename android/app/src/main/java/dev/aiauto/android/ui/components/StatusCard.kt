package dev.aiauto.android.ui.components

/**
 * 界面用途：提供 StatusCard 通用 Compose 组件，统一页面结构与状态表达。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class StatusTone {
    READY,
    ATTENTION,
    INACTIVE,
}

@Composable
fun StatusCard(
    title: String,
    description: String,
    status: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = when (tone) {
        StatusTone.READY -> StatusColors(
            background = MaterialTheme.colorScheme.primaryContainer,
            foreground = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        StatusTone.ATTENTION -> StatusColors(
            background = MaterialTheme.colorScheme.tertiaryContainer,
            foreground = MaterialTheme.colorScheme.onTertiaryContainer,
        )

        StatusTone.INACTIVE -> StatusColors(
            background = MaterialTheme.colorScheme.surfaceVariant,
            foreground = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Card(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.background),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = CircleShape,
                color = colors.foreground,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(
                    text = " ",
                    modifier = Modifier.padding(5.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.foreground,
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.foreground,
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.foreground.copy(alpha = 0.82f),
                )
            }
        }
    }
}

private data class StatusColors(
    val background: Color,
    val foreground: Color,
)
