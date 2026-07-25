package dev.aiauto.android.ui.recording

/**
 * 测试用途：验证生产 N41 dry-run adapter 只读匹配 selector、坐标和 package 条件。
 */

import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import dev.aiauto.android.automation.recording.NormalizedPoint
import dev.aiauto.android.automation.recording.RecordedPredicate
import dev.aiauto.android.automation.recording.editor.ConditionPreviewResult
import dev.aiauto.android.automation.recording.editor.CoordinatePreviewResult
import dev.aiauto.android.automation.recording.editor.PreviewCoordinate
import dev.aiauto.android.automation.recording.editor.SelectorPreviewResult
import dev.aiauto.android.automation.recording.editor.SnapshotPreviewResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRecordingEditorDryRunTest {
    @Test
    fun `adapter matches selector previews coordinate and checks package without execute port BitsUT`() {
        var now = 1_000L
        val root = node(
            className = "android.widget.FrameLayout",
            bounds = UiBounds(0, 0, 1_080, 2_400),
            children = listOf(
                node(
                    resourceId = "com.example:id/save",
                    text = "Save",
                    bounds = UiBounds(100, 200, 300, 400),
                ),
            ),
        )
        val adapter = AndroidRecordingEditorDryRun.createForTest(
            snapshotSource = { AccessibilityResult.Success(root) },
            clock = { now },
        )

        val snapshot = (adapter.snapshot(setOf("com.example")) as
            SnapshotPreviewResult.Available).snapshot
        val target = buildJsonObject {
            put("packageName", JsonPrimitive("com.example"))
            put(
                "selectorCandidates",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("strategy", JsonPrimitive("resourceId"))
                            put("value", JsonPrimitive("com.example:id/save"))
                            put("weight", JsonPrimitive(1.0))
                        },
                    ),
                ),
            )
        }

        assertTrue(adapter.match(snapshot, target) is SelectorPreviewResult.Unique)
        assertEquals(
            CoordinatePreviewResult.InBounds(540, 1_200),
            adapter.preview(
                snapshot,
                PreviewCoordinate(NormalizedPoint(0.5, 0.5), null),
            ),
        )
        assertEquals(
            ConditionPreviewResult.Satisfied,
            adapter.check(
                snapshot,
                RecordedPredicate(
                    kind = "package",
                    operator = "equals",
                    expected = JsonPrimitive("com.example"),
                ),
            ),
        )

        now = 20_000
        assertTrue(
            adapter.match(snapshot, target) is SelectorPreviewResult.Missing,
        )
        assertTrue(
            AndroidRecordingEditorDryRun::class.java.methods.none {
                it.name.contains("execute", ignoreCase = true)
            },
        )
    }

    private fun node(
        resourceId: String? = null,
        text: String? = null,
        className: String = "android.widget.Button",
        bounds: UiBounds,
        children: List<UiNodeSnapshot> = emptyList(),
    ) = UiNodeSnapshot(
        packageName = "com.example",
        className = className,
        resourceId = resourceId,
        text = text,
        contentDescription = null,
        bounds = bounds,
        actions = setOf(NodeAction.CLICK),
        state = UiNodeState(
            enabled = true,
            visibleToUser = true,
            clickable = true,
        ),
        children = children,
    )
}
