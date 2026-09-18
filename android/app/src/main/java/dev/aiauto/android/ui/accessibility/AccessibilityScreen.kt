package dev.aiauto.android.ui.accessibility

/**
 * 功能用途：实现 AccessibilityScreen——无障碍授权范围管理页。
 *
 * 与旧版差异：
 * - 不再手填包名：通过 PackageManager 查询全部可启动应用（Android 11+ 需
 *   QUERY_ALL_PACKAGES，已在 Manifest 声明），列出「图标 + 应用名 + 包名 + 开关」。
 * - 开关即授权/撤销该目标包；关闭最后一个开关即清空授权范围。
 * - 去掉「撤销同意」按钮与包名输入框；披露文案精简为一条琥珀色提示条，安全红线保留。
 * - 「打开无障碍配置」按钮只在系统服务未开启时出现，开启后隐藏（状态来自调用方
 *   AccessibilityServiceStatus 的真实查询，不在本页硬编码）。
 * - target-packages 持久化复用现有 AccessibilitySettingsRepository。
 */

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import dev.aiauto.android.R
import dev.aiauto.android.accessibility.settings.AccessibilitySettings
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.ui.components.NoteText
import dev.aiauto.android.ui.components.NoticeBar
import dev.aiauto.android.ui.components.NoticeTone
import dev.aiauto.android.ui.components.PageDesc
import dev.aiauto.android.ui.components.PageHeading
import dev.aiauto.android.ui.components.ProtoCard
import dev.aiauto.android.ui.components.ProtoPrimaryButton
import dev.aiauto.android.ui.components.ProtoSectionHead
import dev.aiauto.android.ui.components.ScreenScaffold
import dev.aiauto.android.ui.components.TagChip
import dev.aiauto.android.ui.components.TagTone
import dev.aiauto.android.ui.theme.AppColors
import dev.aiauto.android.ui.theme.AppSpacing

/**
 * 已安装可启动应用条目。
 * 图标用 Drawable 承载：列表构建在后台线程，图标在渲染行时才转 Bitmap，避免预解码拖慢首帧。
 */
private data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
)

@Composable
fun AccessibilityScreen(
    repository: AccessibilitySettingsRepository,
    serviceEnabled: Boolean,
    onBack: () -> Unit,
    onSettingsChanged: (AccessibilitySettings) -> Unit,
) {
    val context = LocalContext.current
    val stored = remember(repository) { repository.load() }
    // 已授权目标包集合：与 SharedPreferences 中 target-packages 一一对应。
    var targetPackages by remember { mutableStateOf(stored.targetPackages) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var hasError by remember { mutableStateOf(false) }

    // 后台线程查询可启动应用列表与图标，避免主线程 PackageManager 查询卡顿。
    val installedApps by produceState(initialValue = emptyList<InstalledApp>(), context) {
        value = withContext(Dispatchers.Default) { loadLaunchableApps(context) }
    }

    fun openSystemSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            hasError = true
            feedback = context.getString(R.string.accessibility_settings_unavailable)
        }
    }

    /**
     * 切换某个应用的授权。主动打开开关即视为已阅读披露（disclosureAccepted=true）；
     * 关闭开关即撤销该包授权，无需单独「撤销同意」按钮。
     */
    fun togglePackage(app: InstalledApp, enabled: Boolean) {
        val next = if (enabled) targetPackages + app.packageName else targetPackages - app.packageName
        val settings = AccessibilitySettings(
            disclosureAccepted = true,
            targetPackages = next,
        )
        try {
            repository.save(settings)
        } catch (_: IllegalStateException) {
            hasError = true
            feedback = context.getString(R.string.accessibility_save_failed)
            return
        }
        targetPackages = next
        hasError = false
        feedback = null
        onSettingsChanged(settings)
    }

    ScreenScaffold(
        title = stringResource(R.string.accessibility_page_title),
        onBack = onBack,
        subtitle = stringResource(R.string.accessibility_page_subtitle),
        infoText = stringResource(R.string.accessibility_page_info),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.ScreenHorizontal)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. 页面大标题与精简描述。
            PageHeading(text = stringResource(R.string.accessibility_heading))
            PageDesc(text = stringResource(R.string.accessibility_desc))

            // 2. 安全红线披露（琥珀色提示条，精简表达但不省略）。
            NoticeBar(
                tone = NoticeTone.AMBER,
                title = stringResource(R.string.accessibility_disclosure_title),
                text = stringResource(R.string.accessibility_disclosure_body),
            )

            // 3. 系统服务真实状态（由调用方经 AccessibilityManager 查询，不在本页硬编码）。
            ProtoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.accessibility_service_state),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.Ink,
                    )
                    TagChip(
                        text = if (serviceEnabled) {
                            stringResource(R.string.accessibility_service_on)
                        } else {
                            stringResource(R.string.accessibility_service_off)
                        },
                        tone = if (serviceEnabled) TagTone.SUCCESS else TagTone.WARNING,
                    )
                }
            }

            // 4. 应用授权列表：图标 + 名称 + 包名 + 开关。
            ProtoSectionHead(title = stringResource(R.string.accessibility_apps_section))
            when {
                installedApps.isEmpty() -> Text(
                    text = stringResource(R.string.accessibility_apps_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Muted,
                )

                else -> ProtoCard(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    installedApps.forEachIndexed { index, app ->
                        AppAuthRow(
                            app = app,
                            checked = app.packageName in targetPackages,
                            onCheckedChange = { togglePackage(app, it) },
                        )
                        // 行间 1dp 细线分隔，最后一行不画。
                        if (index != installedApps.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(AppColors.Line),
                            )
                        }
                    }
                }
            }

            // 5. 打开系统无障碍配置：仅在服务未开启时显示；已开启则隐藏。
            if (!serviceEnabled) {
                ProtoPrimaryButton(
                    text = stringResource(R.string.accessibility_open_settings),
                    onClick = { openSystemSettings() },
                )
            }

            // 6. 真实操作反馈。
            feedback?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasError) AppColors.Danger else AppColors.Success,
                )
            }

            // 7. 底部边界说明（精简）。
            NoteText(text = stringResource(R.string.accessibility_bottom_note))
        }
    }
}

/**
 * 单个应用授权行：应用图标（36dp）+ 名称/包名 + 右侧 Switch。
 * 图标在渲染时把 Drawable 解码为 Bitmap，失败则留空位，不阻塞整行。
 */
@Composable
private fun AppAuthRow(
    app: InstalledApp,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val iconBitmap = remember(app.packageName) {
        runCatching { app.icon.toSafeBitmap() }.getOrNull()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.SettingRowHeight)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        } else {
            Spacer(Modifier.size(36.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.Ink,
            )
            Text(
                text = app.packageName,
                fontSize = 11.sp,
                color = AppColors.Muted,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * 查询全部可启动应用：以 ACTION_MAIN + CATEGORY_LAUNCHER 过滤，
 * 自动排除无启动入口的系统核心组件；同时排除本应用自身。
 * Android 11+ 需要 Manifest 声明 QUERY_ALL_PACKAGES 才能拿到完整列表。
 */
private fun loadLaunchableApps(context: Context): List<InstalledApp> {
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

/** Drawable → Bitmap：手写实现，不依赖 core-ktx 扩展；BitmapDrawable 直接复用原位图。 */
private fun Drawable.toSafeBitmap(): Bitmap {
    (this as? BitmapDrawable)?.bitmap?.let { return it }
    val width = intrinsicWidth.takeIf { it > 0 } ?: 48
    val height = intrinsicHeight.takeIf { it > 0 } ?: 48
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}
