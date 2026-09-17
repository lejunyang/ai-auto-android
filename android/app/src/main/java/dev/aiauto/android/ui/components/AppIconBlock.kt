package dev.aiauto.android.ui.components

/**
 * 功能用途：技能卡左上的应用图标块。按原型 skill-list-card 的 bookApp()+noteApp() 双图标
 * 语义，展示该技能目标应用的真实 launcher 图标（最多两个，表达"从来源应用到目标应用"）。
 *
 * 数据与回退边界（不造假图标）：
 * - 目标包名在本机已安装时，通过 PackageManager 加载真实 launcher icon，圆角裁剪后展示。
 * - 包名不存在（如测试 fixture、应用已卸载）或加载失败时，回退到通用 Book/Note 描边图标，
 *   分别用浅蓝、浅绿底色，与原型 app-icon 配色呼应；不伪造某个具体应用的图标。
 * - 没有任何目标包名时，只显示一个 Book 回退图标。
 */

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.aiauto.android.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台加载单个包的 launcher 图标，返回位图 Painter；未安装或失败返回 null。
 * 用 key 绑定包名，切换技能时重新加载。
 */
@Composable
private fun rememberAppIconPainter(packageName: String?, sizePx: Int): BitmapPainter? {
    val context = LocalContext.current
    var painter by remember(packageName, sizePx) { mutableStateOf<BitmapPainter?>(null) }

    LaunchedEffect(packageName, sizePx) {
        if (packageName.isNullOrBlank()) {
            painter = null
            return@LaunchedEffect
        }
        painter = withContext(Dispatchers.IO) {
            loadIconPainter(context, packageName, sizePx)
        }
    }
    return painter
}

/** 在 IO 线程解析应用图标；包不存在或渲染失败时返回 null，不吞异常成假图标。 */
private fun loadIconPainter(context: Context, packageName: String, sizePx: Int): BitmapPainter? {
    return try {
        val pm = context.packageManager
        // 先确认包已安装，避免 getApplicationIcon 对未知包回退成系统默认图标造成误导。
        pm.getApplicationInfo(packageName, 0)
        val drawable: Drawable = pm.getApplicationIcon(packageName)
        val bitmap = drawable.toBitmap(sizePx, sizePx)
        BitmapPainter(bitmap.asImageBitmap())
    } catch (_: Exception) {
        null
    }
}

/**
 * 技能卡应用图标块：最多展示两个目标应用图标，间距 8dp，单个 36dp、圆角 11。
 * 真实图标缺失时按位置回退 Book（浅蓝）/ Note（浅绿）。
 */
@Composable
fun AppIconBlock(
    targetPackages: List<String>,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 36.dp,
) {
    // 最多取两个目标包，对齐原型双应用图标；去重避免同一应用重复显示。
    val packages = remember(targetPackages) { targetPackages.distinct().take(2) }
    val density = LocalDensity.current
    val sizePx = with(density) { iconSize.roundToPx() }

    Row(modifier = modifier) {
        if (packages.isEmpty()) {
            // 无目标包：单个 Book 回退图标。
            FallbackAppIcon(iconSize = iconSize, isFirst = true)
        } else {
            packages.forEachIndexed { index, pkg ->
                if (index > 0) Spacer(Modifier.width(8.dp))
                val painter = rememberAppIconPainter(pkg, sizePx)
                if (painter != null) {
                    // 真实 launcher 图标：圆角裁剪，不额外衬底色（图标自带形状与底色）。
                    Image(
                        painter = painter,
                        contentDescription = pkg,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(iconSize)
                            .clip(RoundedCornerShape(11.dp)),
                    )
                } else {
                    FallbackAppIcon(iconSize = iconSize, isFirst = index == 0)
                }
            }
        }
    }
}

/** 回退图标块：第一个用 Book+浅蓝，第二个用 Note+浅绿（对应原型 read/note 配色）。 */
@Composable
private fun FallbackAppIcon(iconSize: androidx.compose.ui.unit.Dp, isFirst: Boolean) {
    val container = if (isFirst) AppColors.PrimaryContainer else AppColors.TileIconMintBackground
    val tint = if (isFirst) AppColors.Primary else AppColors.Success
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = container,
        modifier = Modifier
            .size(iconSize)
            .padding(0.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Icon(
                imageVector = if (isFirst) AppIcons.Book else AppIcons.Note,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
