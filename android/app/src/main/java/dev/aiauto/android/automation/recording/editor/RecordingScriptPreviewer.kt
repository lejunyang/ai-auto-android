package dev.aiauto.android.automation.recording.editor

/**
 * 功能用途：复用 N39 严格解析与默认脱敏编码，在内存中生成安全导入导出预览。
 */

import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.RecordingScriptStore

sealed interface ImportPreviewResult {
    data class Valid(val script: AutomationScript) : ImportPreviewResult

    data class Invalid(val message: String) : ImportPreviewResult
}

data class ExportPreviewResult(
    val content: String,
)

/**
 * 适配器不调用 save/delete，也不创建自己的临时文件；所有预览结果只存在于内存。
 */
class RecordingScriptPreviewer(
    private val store: RecordingScriptStore,
) {
    fun importPreview(content: String): ImportPreviewResult =
        try {
            ImportPreviewResult.Valid(store.importScript(content))
        } catch (error: Exception) {
            ImportPreviewResult.Invalid(
                error.message ?: "The recording script is invalid",
            )
        }

    fun exportPreview(script: AutomationScript): ExportPreviewResult =
        ExportPreviewResult(store.exportScript(script))
}
