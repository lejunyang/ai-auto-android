package dev.aiauto.android.ui.components

/**
 * 功能用途：共享应用选择组件。查询设备可启动应用并提供统一的
 * 图标+名称+包名+勾选行，供助理页任务输入与录制页目标应用选择复用。
 */

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aiauto.android.ui.theme.AppColors

/** 功能用途：可启动应用数据类，包名+名称+图标。 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
)

/**
 * 功能用途：查询全部可启动应用（ACTION_MAIN+CATEGORY_LAUNCHER，排除自身）。
 * 助理页与录制页共用同一数据源。
 */
fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .mapNotNull { resolve ->
            val activity = resolve.activityInfo ?: return@mapNotNull null
            if (activity.packageName == context.packageName) return@mapNotNull null
            InstalledApp(
                packageName = activity.packageName,
                label = activity.loadLabel(pm).toString(),
                icon = activity.loadIcon(pm),
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/** Drawable → Bitmap：手写实现，不依赖 core-ktx 扩展。 */
fun Drawable.toSafeBitmap(): Bitmap {
    (this as? BitmapDrawable)?.bitmap?.let { return it }
    val width = intrinsicWidth.takeIf { it > 0 } ?: 48
    val height = intrinsicHeight.takeIf { it > 0 } ?: 48
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}

/**
 * 功能用途：应用选择弹窗的单行渲染：图标+名称+包名+勾选圈。
 * 整行无涟漪 clickable。
 */
@Composable
fun AppPickerRow(
    app: InstalledApp,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .protoClickable(onClick = onToggle)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 应用图标 36dp，失败留 PrimaryContainer 占位。
        val bitmap = remember(app.packageName) { runCatching { app.icon.toSafeBitmap() }.getOrNull() }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.label,
                modifier = Modifier.size(36.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(AppColors.PrimaryContainer, RoundedCornerShape(8.dp)),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(text = app.label, fontSize = 14.sp, color = AppColors.Ink)
            Text(text = app.packageName, fontSize = 10.sp, color = AppColors.Muted)
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (checked) AppColors.Primary else AppColors.Line),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = AppColors.Surface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
