package dev.aiauto.android.ui.recording

/**
 * 功能用途：按原型 record/editor 视图重构的技能编排编辑器。
 *
 * 交互流：录制后进入 → 步骤列表（时间轴 rail）→ 点步骤弹出"编辑步骤"底部弹窗
 * → "用一句话表达"tab 调用 SkillLanguageGenerator 生成描述建议（仅建议不覆盖步骤）
 * → "技能名称"弹窗改名 → 底部"保存"持久化。
 *
 * 保留真实编辑能力：选择器、坐标、等待谓词、重试、失败策略、撤销/重做、复制、
 * 预检（dry-run）、revision 冲突与 dirty 丢弃确认。所有可见文案走 stringResource。
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import dev.aiauto.android.R
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.editor.DryRunStepStatus
import dev.aiauto.android.automation.recording.editor.language.LanguageGenerationResult
import dev.aiauto.android.ui.components.NoteText
import dev.aiauto.android.ui.components.ProtoModalSheet
import dev.aiauto.android.ui.theme.AppColors

@Composable
fun RecordingEditorScreen(
    state: RecordingEditorUiState,
    observationHolder: RecordingObservationHolder?,
    onSelectStep: (String) -> Unit,
    onMoveStep: (String, Int) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDeleteStep: (String) -> Unit,
    onDuplicateStep: (String) -> Unit,
    onUpdateForm: ((RecordingStepFormState) -> RecordingStepFormState) -> Unit,
    onSubmitForm: () -> Unit,
    onObservationSelection: (ObservationSelection) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onDryRun: () -> Unit,
    onBack: () -> Unit,
    onDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
    onGenerateDescription: suspend () -> LanguageGenerationResult,
    onUpdateDescription: (String) -> Unit,
    onRename: (String) -> Unit,
) {
    DisposableEffect(observationHolder) {
        onDispose { observationHolder?.detach() }
    }
    // 编辑器 tab：步骤编排 / 用一句话表达（语言归纳建议，不覆盖步骤）。
    var editorTab by remember { mutableStateOf(EditorTab.STEPS) }
    // 一句话表达的描述文本与生成状态。
    var descriptionText by remember { mutableStateOf(state.script.description) }
    var isGenerating by remember { mutableStateOf(false) }
    var generateMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    // "编辑步骤"底部弹窗可见性（与 state.selectedStepId 联动）。
    var editModalVisible by remember { mutableStateOf(false) }
    // "技能名称"弹窗草稿与可见性。
    var renameModalVisible by remember { mutableStateOf(false) }
    var renameDraft by remember { mutableStateOf(state.script.name) }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶部栏：返回 + 页面标题。
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.editor_back))
            }
            Text(
                text = stringResource(R.string.editor_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 技能名 + 改名入口（原型 .editor 顶部标题行）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.script.name,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.Ink,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                renameDraft = state.script.name
                renameModalVisible = true
            }) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.editor_rename_cd),
                    tint = AppColors.Muted,
                )
            }
        }
        // dirty / 已保存状态。
        Text(
            text = if (state.isDirty) {
                stringResource(R.string.editor_dirty)
            } else {
                stringResource(R.string.editor_saved_revision, state.script.revision)
            },
            color = if (state.isDirty) AppColors.WarningNoticeText else AppColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        // 分段切换：步骤编排 / 用一句话表达（原型 .segmented）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFEAF0F7), RoundedCornerShape(13.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SegmentTab(
                selected = editorTab == EditorTab.STEPS,
                onClick = { editorTab = EditorTab.STEPS },
                label = stringResource(R.string.editor_tab_steps),
                modifier = Modifier.weight(1f),
            )
            SegmentTab(
                selected = editorTab == EditorTab.LANGUAGE,
                onClick = { editorTab = EditorTab.LANGUAGE },
                label = stringResource(R.string.editor_tab_language),
                modifier = Modifier.weight(1f),
            )
        }

        when (editorTab) {
            EditorTab.STEPS -> {
                // 步骤计数 + 撤销/重做工具条。
                val enabledCount = state.script.steps.count { it.enabled }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.editor_steps_summary,
                            state.script.steps.size,
                            enabledCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Muted,
                    )
                    Row {
                        IconButton(
                            onClick = onUndo,
                            enabled = state.canUndo,
                            modifier = Modifier.testTag(RecordingTestTags.EDITOR_UNDO),
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = stringResource(R.string.editor_undo_cd))
                        }
                        IconButton(
                            onClick = onRedo,
                            enabled = state.canRedo,
                            modifier = Modifier.testTag(RecordingTestTags.EDITOR_REDO),
                        ) {
                            Icon(Icons.Default.Redo, contentDescription = stringResource(R.string.editor_redo_cd))
                        }
                    }
                }

                // 步骤时间轴列表（原型 .steps / .step-rail）。
                state.script.steps.forEachIndexed { index, step ->
                    StepRail(
                        step = step,
                        index = index,
                        isLast = index == state.script.steps.lastIndex,
                        selected = step.id == state.selectedStepId,
                        onOpen = {
                            onSelectStep(step.id)
                            editModalVisible = true
                        },
                        onMoveUp = { onMoveStep(step.id, (index - 1).coerceAtLeast(0)) },
                        onMoveDown = {
                            onMoveStep(
                                step.id,
                                (index + 1).coerceAtMost(state.script.steps.lastIndex),
                            )
                        },
                        onToggle = { onSetEnabled(step.id, !step.enabled) },
                        onDuplicate = { onDuplicateStep(step.id) },
                        onDelete = { onDeleteStep(step.id) },
                    )
                }
                NoteText(stringResource(R.string.editor_steps_hint))

                observationHolder?.let { holder ->
                    AuthorizedObservationSurface(
                        holder = holder,
                        onSelection = onObservationSelection,
                    )
                }
                state.dryRunReport?.let { report ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(
                                    if (report.succeeded) R.string.editor_dry_run_ok
                                    else R.string.editor_dry_run_failed,
                                ),
                                fontWeight = FontWeight.SemiBold,
                            )
                            report.steps.forEach { result ->
                                Text(
                                    "${result.stepId}: ${result.status} " +
                                        "${result.errorCode?.name.orEmpty()}",
                                    color = if (result.status == DryRunStepStatus.FAILED) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }

            EditorTab.LANGUAGE -> {
                // 用一句话表达：AI 生成技能描述建议，不覆盖步骤。
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.editor_lang_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = descriptionText,
                        onValueChange = { descriptionText = it },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        placeholder = { Text(stringResource(R.string.editor_lang_placeholder)) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // stringResource 必须在组合上下文取，协程内只做赋值。
                        val msgChanged = stringResource(R.string.editor_lang_result_changed)
                        val msgSame = stringResource(R.string.editor_lang_result_same)
                        val msgNotConfigured = stringResource(R.string.editor_lang_not_configured)
                        val msgErrorPattern = stringResource(R.string.editor_lang_error)
                        Button(
                            onClick = {
                                isGenerating = true
                                generateMessage = null
                                coroutineScope.launch {
                                    when (val result = onGenerateDescription()) {
                                        is LanguageGenerationResult.Success -> {
                                            descriptionText = result.description
                                            generateMessage = if (result.changedFromPrevious) {
                                                msgChanged
                                            } else {
                                                msgSame
                                            }
                                        }

                                        LanguageGenerationResult.NotConfigured -> {
                                            generateMessage = msgNotConfigured
                                        }

                                        is LanguageGenerationResult.Error -> {
                                            generateMessage =
                                                String.format(msgErrorPattern, result.message)
                                        }
                                    }
                                    isGenerating = false
                                }
                            },
                            enabled = !isGenerating,
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.editor_lang_generate))
                        }
                        OutlinedButton(onClick = { descriptionText = "" }) {
                            Text(stringResource(R.string.editor_lang_clear))
                        }
                    }
                    generateMessage?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.Muted,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onUpdateDescription(descriptionText) },
                            enabled = descriptionText.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.editor_lang_apply))
                        }
                    }
                    NoteText(stringResource(R.string.editor_lang_confirm_hint))
                }
            }
        }

        state.revisionConflict?.let { conflict ->
            Text(
                text = stringResource(
                    R.string.editor_revision_conflict,
                    conflict.expectedRevision,
                    conflict.current.revision,
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // 次级操作：复制脚本。
        OutlinedButton(
            onClick = onCopy,
            modifier = Modifier.testTag(RecordingTestTags.EDITOR_COPY),
        ) {
            Text(stringResource(R.string.editor_copy))
        }

        // 底部主操作行：预检 + 保存（原型 .sticky-bottom）。
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(
                onClick = onDryRun,
                modifier = Modifier
                    .weight(1f)
                    .testTag(RecordingTestTags.EDITOR_DRY_RUN),
            ) {
                Text(stringResource(R.string.editor_dry_run))
            }
            Button(
                onClick = onSave,
                enabled = state.isDirty,
                modifier = Modifier
                    .weight(1f)
                    .testTag(RecordingTestTags.EDITOR_SAVE),
            ) {
                Text(stringResource(R.string.editor_save))
            }
        }
    }

    // "编辑步骤"底部弹窗：承载真实步骤表单（选择器/坐标/等待/重试/失败策略/notes）。
    if (editModalVisible && state.stepForm != null && state.selectedStepId != null) {
        ProtoModalSheet(
            visible = true,
            onDismiss = { editModalVisible = false },
            title = stringResource(R.string.editor_step_modal_title),
        ) {
            NoteText(stringResource(R.string.editor_step_modal_hint))
            Spacer(Modifier.height(8.dp))
            EditorStepForm(
                form = state.stepForm,
                error = state.formError,
                onUpdate = onUpdateForm,
                onSubmit = onSubmitForm,
            )
        }
    }

    // "技能名称"弹窗：确认后才 onRename，随保存持久化。
    if (renameModalVisible) {
        ProtoModalSheet(
            visible = true,
            onDismiss = { renameModalVisible = false },
            title = stringResource(R.string.editor_rename_title),
        ) {
            NoteText(stringResource(R.string.editor_rename_hint))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = renameDraft,
                onValueChange = { if (it.length <= 128) renameDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (renameDraft.isNotBlank()) {
                        onRename(renameDraft)
                    }
                    renameModalVisible = false
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = renameDraft.isNotBlank(),
            ) {
                Text(stringResource(R.string.editor_rename_save))
            }
        }
    }

    if (state.showDiscardConfirmation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissDiscard,
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_body)) },
            confirmButton = {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.editor_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDiscard) {
                    Text(stringResource(R.string.editor_discard_cancel))
                }
            },
        )
    }
}

/** 分段 tab 按钮：选中白底、9dp 圆角。 */
@Composable
private fun SegmentTab(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 35.dp)
            .background(
                color = if (selected) AppColors.Surface else Color.Transparent,
                shape = RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) AppColors.Ink else Color(0xFF6B7B90),
        )
    }
}

/** 步骤时间轴一行：左侧序号圆 + 连接线，右侧步骤卡片（原型 .step-rail / .step-body）。 */
@Composable
private fun StepRail(
    step: RecordedStep,
    index: Int,
    isLast: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        // 左侧轨道：序号圆 + 连接线。
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(30.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = if (selected) AppColors.Primary else AppColors.Surface,
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) AppColors.Primary else Color(0xFFDCE4EF),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${index + 1}",
                    fontSize = 11.sp,
                    color = if (selected) AppColors.Surface else Color(0xFF6C7C91),
                )
            }
            if (!isLast) {
                Spacer(
                    Modifier
                        .width(1.dp)
                        .height(15.dp)
                        .background(Color(0xFFDBE4F1)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // 右侧步骤卡片：点击打开编辑弹窗。
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 5.dp)
                .background(
                    color = if (selected) Color(0xFFF5F8FF) else AppColors.Surface,
                    shape = RoundedCornerShape(15.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (selected) Color(0xFFA4C0F7) else AppColors.Line,
                    shape = RoundedCornerShape(15.dp),
                )
                .testTag(RecordingTestTags.editorStep(step.id))
                .clickable(onClick = onOpen)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(modifier = Modifier.alpha(if (step.enabled) 1f else 0.46f)) {
                Text(
                    text = step.action.type,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Ink,
                )
                if (!step.notes.isNullOrBlank()) {
                    Text(
                        text = step.notes,
                        fontSize = 11.sp,
                        color = AppColors.Muted,
                    )
                }
            }
            // 步骤操作行：顺序 / 启停 / 复制 / 删除。
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.editor_step_up_cd),
                        tint = AppColors.Muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.editor_step_down_cd),
                        tint = AppColors.Muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(0.dp))
                TextButton(onClick = onToggle) {
                    Text(
                        if (step.enabled) stringResource(R.string.editor_step_disable)
                        else stringResource(R.string.editor_step_enable),
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.editor_step_duplicate_cd),
                        tint = AppColors.Muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.editor_step_delete_cd),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** 步骤字段表单：保留选择器、坐标、等待谓词、重试、失败策略、notes 的真实编辑。 */
@Composable
private fun EditorStepForm(
    form: RecordingStepFormState,
    error: String?,
    onUpdate: ((RecordingStepFormState) -> RecordingStepFormState) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("selector strategy", form.selectorStrategy) {
            onUpdate { current -> current.copy(selectorStrategy = it) }
        }
        Field("selector value", form.selectorValue) {
            onUpdate { current -> current.copy(selectorValue = it) }
        }
        Field("selector weight", form.selectorWeight) {
            onUpdate { current -> current.copy(selectorWeight = it) }
        }
        OutlinedButton(
            onClick = {
                onUpdate { current ->
                    current.copy(selectorRequired = !current.selectorRequired)
                }
            },
        ) {
            Text(if (form.selectorRequired) "selector 必须匹配" else "selector 可选")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("X", form.coordinateX, Modifier.weight(1f)) {
                onUpdate { current -> current.copy(coordinateX = it) }
            }
            Field("Y", form.coordinateY, Modifier.weight(1f)) {
                onUpdate { current -> current.copy(coordinateY = it) }
            }
        }
        Field("retry attempts", form.retryMaxAttempts) {
            onUpdate { current -> current.copy(retryMaxAttempts = it) }
        }
        Field("retry backoff", form.retryBackoffMs) {
            onUpdate { current -> current.copy(retryBackoffMs = it) }
        }
        Field("retry multiplier", form.retryBackoffMultiplier) {
            onUpdate { current -> current.copy(retryBackoffMultiplier = it) }
        }
        Field("retry max backoff", form.retryMaxBackoffMs) {
            onUpdate { current -> current.copy(retryMaxBackoffMs = it) }
        }
        Field("failure policy", form.failurePolicy) {
            onUpdate { current -> current.copy(failurePolicy = it) }
        }
        Field("notes", form.notes) {
            onUpdate { current -> current.copy(notes = it) }
        }
        PredicateEditor("动作前等待", form.waitBefore) { value ->
            onUpdate { current -> current.copy(waitBefore = value) }
        }
        PredicateEditor("动作后等待", form.waitAfter) { value ->
            onUpdate { current -> current.copy(waitAfter = value) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(RecordingTestTags.EDITOR_FORM_SUBMIT),
        ) {
            Text(stringResource(R.string.editor_form_submit))
        }
    }
}

@Composable
private fun PredicateEditor(
    title: String,
    value: PredicateFormState,
    onChange: (PredicateFormState) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    OutlinedButton(onClick = { onChange(value.copy(enabled = !value.enabled)) }) {
        Text(if (value.enabled) "已启用" else "未启用")
    }
    if (!value.enabled) return
    Field("predicate kind", value.kind) { onChange(value.copy(kind = it)) }
    Field("predicate operator", value.operator) { onChange(value.copy(operator = it)) }
    Field("predicate expected", value.expected) { onChange(value.copy(expected = it)) }
    Field("stable duration", value.stableDurationMs) {
        onChange(value.copy(stableDurationMs = it))
    }
    Field("predicate timeout", value.timeoutMs) { onChange(value.copy(timeoutMs = it)) }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
    )
}

@Composable
private fun AuthorizedObservationSurface(
    holder: RecordingObservationHolder,
    onSelection: (ObservationSelection) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .testTag(RecordingTestTags.EDITOR_OBSERVATION)
            .pointerInput(holder) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var current = down.position
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        current = change.position
                        if ((current - down.position).getDistance() >= touchSlop) {
                            dragged = true
                            change.consume()
                        }
                        if (!change.pressed) {
                            break
                        }
                    }
                    val width = size.width.toDouble()
                    val height = size.height.toDouble()
                    val selection = if (dragged) {
                        holder.selectNormalizedBounds(
                            startX = down.position.x / width,
                            startY = down.position.y / height,
                            endX = current.x / width,
                            endY = current.y / height,
                        )
                    } else {
                        holder.selectNormalized(
                            x = down.position.x / width,
                            y = down.position.y / height,
                        )
                    }
                    onSelection(selection)
                }
            },
    ) {
        if (holder.drawAuthorizedObservation(this)) {
            drawCircle(
                color = Color(0xFF0F766E),
                radius = 8.dp.toPx(),
                center = Offset(size.width / 2, size.height / 2),
            )
        }
    }
}

/** 编辑器 tab 枚举：步骤编排 / 用一句话表达（语言归纳建议）。 */
private enum class EditorTab {
    STEPS,
    LANGUAGE,
}
