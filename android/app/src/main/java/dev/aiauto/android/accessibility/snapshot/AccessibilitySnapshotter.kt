package dev.aiauto.android.accessibility.snapshot

/**
 * 功能用途：实现 AccessibilitySnapshotter 对应的无障碍节点快照与敏感信息最小化处理。
 */

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

    /**
     * 把平台动作 id 映射为领域动作。
     *
     * 这张表是「节点支持什么」的唯一来源：不在表里的动作即使节点实际支持，
     * 快照也不会上报，于是路由层会认为不可用而降级到手势或直接失败。
     * 因此新增动作能力时必须同时在这里登记。
     */
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
        AccessibilityNodeInfo.ACTION_FOCUS -> NodeAction.FOCUS
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> NodeAction.CLEAR_FOCUS
        AccessibilityNodeInfo.ACTION_SELECT -> NodeAction.SELECT
        AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> NodeAction.CLEAR_SELECTION
        AccessibilityNodeInfo.ACTION_EXPAND -> NodeAction.EXPAND
        AccessibilityNodeInfo.ACTION_COLLAPSE -> NodeAction.COLLAPSE
        AccessibilityNodeInfo.ACTION_DISMISS -> NodeAction.DISMISS
        AccessibilityNodeInfo.ACTION_COPY -> NodeAction.COPY
        AccessibilityNodeInfo.ACTION_CUT -> NodeAction.CUT
        AccessibilityNodeInfo.ACTION_PASTE -> NodeAction.PASTE
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> NodeAction.SET_SELECTION
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id ->
            NodeAction.SET_PROGRESS

        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id ->
            NodeAction.SCROLL_TO_POSITION

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
