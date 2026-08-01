package dev.aiauto.android.ui.recording

/**
 * 功能用途：展示可嵌入的 Compose 录制编辑器，并把全部编辑事件转发给 N41 状态适配层。
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.editor.DryRunStepStatus

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
) {
    DisposableEffect(observationHolder) {
        onDispose { observationHolder?.detach() }
    }
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.script.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            if (state.isDirty) "有未保存更改" else "已保存 revision ${state.script.revision}",
            color = if (state.isDirty) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onUndo,
                enabled = state.canUndo,
                modifier = Modifier.testTag(RecordingTestTags.EDITOR_UNDO),
            ) { Text("撤销") }
            OutlinedButton(
                onClick = onRedo,
                enabled = state.canRedo,
                modifier = Modifier.testTag(RecordingTestTags.EDITOR_REDO),
            ) { Text("重做") }
            Button(
                onClick = onSave,
                enabled = state.isDirty,
                modifier = Modifier.testTag(RecordingTestTags.EDITOR_SAVE),
            ) { Text("保存") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier.testTag(RecordingTestTags.EDITOR_COPY),
            ) { Text("复制脚本") }
            OutlinedButton(
                onClick = onDryRun,
                modifier = Modifier.testTag(RecordingTestTags.EDITOR_DRY_RUN),
            ) { Text("Dry-run") }
            TextButton(onClick = onBack) { Text("返回") }
        }
        state.script.steps.forEachIndexed { index, step ->
            EditorStepCard(
                step = step,
                index = index,
                selected = step.id == state.selectedStepId,
                onSelect = { onSelectStep(step.id) },
                onMoveUp = { onMoveStep(step.id, (index - 1).coerceAtLeast(0)) },
                onMoveDown = {
                    onMoveStep(
                        step.id,
                        (index + 1).coerceAtMost(state.script.steps.lastIndex),
                    )
                },
                onToggle = { onSetEnabled(step.id, !step.enabled) },
                onDelete = { onDeleteStep(step.id) },
                onDuplicate = { onDuplicateStep(step.id) },
            )
        }
        state.stepForm?.let { form ->
            EditorStepForm(
                form = form,
                error = state.formError,
                onUpdate = onUpdateForm,
                onSubmit = onSubmitForm,
            )
        }
        observationHolder?.let { holder ->
            AuthorizedObservationSurface(
                holder = holder,
                onSelection = onObservationSelection,
            )
        }
        state.dryRunReport?.let { report ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(if (report.succeeded) "Dry-run 可执行" else "Dry-run 失败")
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
        state.revisionConflict?.let { conflict ->
            Text(
                "revision 冲突：expected=${conflict.expectedRevision}, " +
                    "current=${conflict.current.revision}",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (state.showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissDiscard,
            title = { Text("丢弃未保存更改？") },
            text = { Text("离开会恢复最后保存的 revision。") },
            confirmButton = {
                TextButton(onClick = onDiscard) { Text("丢弃") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDiscard) { Text("继续编辑") }
            },
        )
    }
}

@Composable
private fun EditorStepCard(
    step: RecordedStep,
    index: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecordingTestTags.editorStep(step.id))
            .clickable(onClick = onSelect),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${index + 1}. ${step.action.type}${if (selected) " · 编辑中" else ""}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onMoveUp) { Text("上移") }
                TextButton(onClick = onMoveDown) { Text("下移") }
                TextButton(onClick = onToggle) { Text(if (step.enabled) "停用" else "启用") }
                TextButton(onClick = onDuplicate) { Text("复制") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun EditorStepForm(
    form: RecordingStepFormState,
    error: String?,
    onUpdate: ((RecordingStepFormState) -> RecordingStepFormState) -> Unit,
    onSubmit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("步骤字段", style = MaterialTheme.typography.titleMedium)
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
                modifier = Modifier.testTag(RecordingTestTags.EDITOR_FORM_SUBMIT),
            ) { Text("应用步骤表单") }
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
                color = androidx.compose.ui.graphics.Color(0xFF0F766E),
                radius = 8.dp.toPx(),
                center = Offset(size.width / 2, size.height / 2),
            )
        }
    }
}
