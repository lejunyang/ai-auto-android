package dev.aiauto.android.ui.recording

/**
 * 功能用途：实现 RecordingTestTags 对应的录制、脚本预览、保存与回放交互。
 */

internal object RecordingTestTags {
    const val START = "recording-start"
    const val PAUSE = "recording-pause"
    const val RESUME = "recording-resume"
    const val FINISH = "recording-finish"
    const val REPLAY = "recording-replay"
    const val REPLAY_RESULT = "recording-replay-result"
    const val REPLAY_INTERVENTION = "recording-replay-intervention"
    const val EDITOR_UNDO = "recording-editor-undo"
    const val EDITOR_REDO = "recording-editor-redo"
    const val EDITOR_SAVE = "recording-editor-save"
    const val EDITOR_DRY_RUN = "recording-editor-dry-run"
    const val EDITOR_COPY = "recording-editor-copy"
    const val EDITOR_FORM_SUBMIT = "recording-editor-form-submit"
    const val EDITOR_OBSERVATION = "recording-editor-observation"

    fun step(number: Int): String = "recording-step-$number"

    fun secretInput(alias: String): String = "recording-secret-$alias"

    fun editorStep(id: String): String = "recording-editor-step-$id"
}
