package dev.aiauto.android.accessibility.snapshot

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState

class AccessibilitySnapshotter(
    private val redactor: SensitiveNodeRedactor = SensitiveNodeRedactor(),
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxNodes: Int = DEFAULT_MAX_NODES,
) {
    fun snapshot(root: AccessibilityNodeInfo): AccessibilityResult<UiNodeSnapshot> {
        val budget = NodeBudget(maxNodes)
        return try {
            val snapshot = copyNode(root, depth = 0, budget = budget)
            AccessibilityResult.Success(redactor.redact(snapshot))
        } catch (error: SnapshotLimitException) {
            AccessibilityResult.Failure(
                code = AccessibilityErrorCode.SNAPSHOT_FAILED,
                message = error.message ?: "The accessibility tree exceeds the snapshot limit",
                retryable = true,
            )
        } catch (_: IllegalStateException) {
            AccessibilityResult.Failure(
                code = AccessibilityErrorCode.SNAPSHOT_FAILED,
                message = "The accessibility window changed while it was observed",
                retryable = true,
            )
        }
    }

    private fun copyNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: NodeBudget,
    ): UiNodeSnapshot {
        if (depth > maxDepth) {
            throw SnapshotLimitException("The accessibility tree exceeds $maxDepth levels")
        }
        budget.consume()

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val children = buildList {
            repeat(node.childCount) { index ->
                val child = node.getChild(index) ?: return@repeat
                try {
                    add(copyNode(child, depth + 1, budget))
                } finally {
                    child.recycleSafely()
                }
            }
        }
        return UiNodeSnapshot(
            packageName = node.packageName?.toString(),
            className = node.className?.toString(),
            resourceId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            bounds = UiBounds(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
            ),
            actions = node.actionList.mapNotNullTo(mutableSetOf()) { action ->
                action.id.toNodeAction()
            },
            state = UiNodeState(
                checkable = node.isCheckable,
                checked = node.isChecked,
                clickable = node.isClickable,
                enabled = node.isEnabled,
                editable = node.isEditable,
                focusable = node.isFocusable,
                focused = node.isFocused,
                longClickable = node.isLongClickable,
                password = node.isPassword,
                scrollable = node.isScrollable,
                selected = node.isSelected,
                visibleToUser = node.isVisibleToUser,
            ),
            children = children,
        )
    }

    private fun Int.toNodeAction(): NodeAction? = when (this) {
        AccessibilityNodeInfo.ACTION_CLICK -> NodeAction.CLICK
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> NodeAction.LONG_CLICK
        AccessibilityNodeInfo.ACTION_SET_TEXT -> NodeAction.SET_TEXT
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> NodeAction.SCROLL_FORWARD
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> NodeAction.SCROLL_BACKWARD
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> NodeAction.SCROLL_UP
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> NodeAction.SCROLL_DOWN
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> NodeAction.SCROLL_LEFT
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> NodeAction.SCROLL_RIGHT
        else -> null
    }

    private class NodeBudget(private var remaining: Int) {
        fun consume() {
            if (remaining-- <= 0) {
                throw SnapshotLimitException(
                    "The accessibility tree exceeds the $DEFAULT_MAX_NODES node limit",
                )
            }
        }
    }

    private class SnapshotLimitException(message: String) : IllegalStateException(message)

    private companion object {
        const val DEFAULT_MAX_DEPTH = 64
        const val DEFAULT_MAX_NODES = 4_000
    }
}

@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.recycleSafely() {
    runCatching(::recycle)
}
