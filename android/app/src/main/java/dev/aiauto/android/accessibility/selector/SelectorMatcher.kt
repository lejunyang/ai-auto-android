package dev.aiauto.android.accessibility.selector

/**
 * 功能用途：实现 SelectorMatcher 的语义选择器匹配与置信度约束，避免歧义目标被猜测执行。
 */

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

import dev.aiauto.android.accessibility.model.NodePath
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorStrategy
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiWindowSnapshot

sealed interface SelectorMatch {
    data class Found(
        val path: NodePath,
        val node: UiNodeSnapshot,
        val score: Double,
    ) : SelectorMatch

    data class NotFound(val bestScore: Double = 0.0) : SelectorMatch

    data class Ambiguous(
        val score: Double,
        val candidateCount: Int,
    ) : SelectorMatch
}

class SelectorMatcher(
    private val minimumScore: Double = DEFAULT_MINIMUM_SCORE,
    private val uniquenessMargin: Double = DEFAULT_UNIQUENESS_MARGIN,
) {
    init {
        require(minimumScore in 0.0..1.0)
        require(uniquenessMargin in 0.0..1.0)
    }

    fun match(root: UiNodeSnapshot, target: NodeTarget): SelectorMatch =
        matchCandidateRoots(
            candidateRoots = listOf(CandidateRoot(windowId = null, layer = 0, root = root)),
            target = target,
        )

    /**
     * 在多个窗口内匹配同一个目标。
     *
     * 跨窗口而非逐窗口匹配是必要的：对话框弹出时宿主 Activity 仍在窗口列表里，
     * 若逐窗口取第一个命中，就可能点到被对话框盖住的按钮。这里把所有窗口的节点
     * 汇入同一个候选集合统一排序，再由 layer 在同分时决定胜者——
     * 最上层的可见节点才是用户真正能点到的那个。
     */
    fun match(windows: List<UiWindowSnapshot>, target: NodeTarget): SelectorMatch =
        matchCandidateRoots(
            candidateRoots = windows.map { window ->
                CandidateRoot(
                    windowId = window.windowId,
                    layer = window.layer,
                    root = window.root,
                )
            },
            target = target,
        )

    // 名字不能也叫 match：两个入参都是 List，泛型擦除后 JVM 签名相同会冲突。
    private fun matchCandidateRoots(
        candidateRoots: List<CandidateRoot>,
        target: NodeTarget,
    ): SelectorMatch {
        if (!validCandidates(target.selectorCandidates)) {
            return SelectorMatch.NotFound()
        }

        val scoredNodes = buildList {
            candidateRoots.forEach { candidateRoot ->
                visit(
                    node = candidateRoot.root,
                    path = emptyList(),
                    windowId = candidateRoot.windowId,
                    layer = candidateRoot.layer,
                    ancestors = emptyList(),
                    target = target,
                    destination = this,
                )
            }
        }.sortedWith(
            compareByDescending<ScoredNode> { it.score }
                .thenByDescending { it.fingerprintScore }
                .thenByDescending { it.layer },
        )

        val best = scoredNodes.firstOrNull() ?: return SelectorMatch.NotFound()
        if (best.score < minimumScore) {
            return SelectorMatch.NotFound(bestScore = best.score)
        }

        val competing = scoredNodes.drop(1)
            .takeWhile { best.score - it.score < uniquenessMargin }
            .filter { it.score >= minimumScore }
        if (
            competing.isNotEmpty() &&
            !fingerprintUniquelySeparates(best, competing) &&
            !layerUniquelySeparates(best, competing)
        ) {
            return SelectorMatch.Ambiguous(
                score = best.score,
                candidateCount = competing.size + 1,
            )
        }

        return SelectorMatch.Found(
            path = NodePath(indices = best.path, windowId = best.windowId),
            node = best.node,
            score = best.score,
        )
    }

    private fun visit(
        node: UiNodeSnapshot,
        path: List<Int>,
        windowId: Int?,
        layer: Int,
        ancestors: List<UiNodeSnapshot>,
        target: NodeTarget,
        destination: MutableList<ScoredNode>,
    ) {
        if (
            node.state.visibleToUser &&
            (target.packageName == null || node.packageName == target.packageName)
        ) {
            score(node, ancestors, target)?.let { score ->
                destination += ScoredNode(
                    path = path,
                    windowId = windowId,
                    layer = layer,
                    node = node,
                    score = score,
                    fingerprintScore = fingerprintScore(node, target.fingerprint),
                )
            }
        }

        val childAncestors = ancestors + node
        node.children.forEachIndexed { index, child ->
            visit(
                node = child,
                path = path + index,
                windowId = windowId,
                layer = layer,
                ancestors = childAncestors,
                target = target,
                destination = destination,
            )
        }
    }

    private fun score(
        node: UiNodeSnapshot,
        ancestors: List<UiNodeSnapshot>,
        target: NodeTarget,
    ): Double? {
        val totalWeight = target.selectorCandidates.sumOf(SelectorCandidate::weight)
        if (totalWeight <= 0.0) {
            return null
        }

        var matchedWeight = 0.0
        target.selectorCandidates.forEach { candidate ->
            val matched = candidateMatches(candidate, node, ancestors)
            if (candidate.required && !matched) {
                return null
            }
            if (matched) {
                matchedWeight += candidate.weight
            }
        }
        if (matchedWeight == 0.0) {
            return null
        }
        return (matchedWeight / totalWeight).coerceIn(0.0, 1.0)
    }

    private fun candidateMatches(
        candidate: SelectorCandidate,
        node: UiNodeSnapshot,
        ancestors: List<UiNodeSnapshot>,
    ): Boolean {
        val expected = candidate.value.normalized()
        return when (candidate.strategy) {
            SelectorStrategy.RESOURCE_ID -> node.resourceId.normalized() == expected
            SelectorStrategy.CONTENT_DESCRIPTION ->
                node.contentDescription.normalized() == expected

            SelectorStrategy.TEXT -> node.text.normalized() == expected
            SelectorStrategy.ROLE -> roleOf(node) == expected
            SelectorStrategy.ANCESTOR -> ancestors.any { ancestor ->
                listOf(
                    ancestor.resourceId,
                    ancestor.contentDescription,
                    ancestor.text,
                    ancestor.className,
                    roleOf(ancestor),
                ).any { it.normalized() == expected }
            }

            SelectorStrategy.CLASS_NAME -> node.className.normalized() == expected
            SelectorStrategy.PACKAGE -> node.packageName.normalized() == expected
            SelectorStrategy.FINGERPRINT ->
                NodeFingerprint.create(node).equals(candidate.value, ignoreCase = true)
        }
    }

    private fun fingerprintScore(
        node: UiNodeSnapshot,
        fingerprint: Map<String, String?>,
    ): Double {
        if (fingerprint.isEmpty()) {
            return 0.0
        }
        val actual = mapOf(
            "packageName" to node.packageName,
            "className" to node.className,
            "resourceId" to node.resourceId,
            "text" to node.text,
            "contentDescription" to node.contentDescription,
            "clickable" to node.state.clickable.toString(),
            "editable" to node.state.editable.toString(),
            "scrollable" to node.state.scrollable.toString(),
        )
        val matches = fingerprint.count { (key, value) ->
            actual[key].normalized() == value.normalized()
        }
        return matches.toDouble() / fingerprint.size
    }

    private fun fingerprintUniquelySeparates(
        best: ScoredNode,
        competing: List<ScoredNode>,
    ): Boolean =
        best.fingerprintScore > 0.0 &&
            competing.all { best.fingerprintScore - it.fingerprintScore >= 0.25 }

    /**
     * 当同分候选来自不同窗口且最上层唯一时，按 z 序判定胜者。
     *
     * 这条规则只在跨窗口场景生效：对话框弹出时宿主 Activity 里的同名按钮
     * 仍会命中，但它被盖住了，用户点不到。若不用 layer 分离，这类界面会
     * 一律报 SELECTOR_AMBIGUOUS——那是把「有唯一正确答案」误判成「无法决定」。
     * 同一窗口内的同分候选仍然保持歧义，因为 z 序无法区分它们。
     */
    private fun layerUniquelySeparates(
        best: ScoredNode,
        competing: List<ScoredNode>,
    ): Boolean =
        best.windowId != null &&
            competing.all { it.windowId != null && it.layer < best.layer }

    private fun validCandidates(candidates: List<SelectorCandidate>): Boolean =
        candidates.isNotEmpty() &&
            candidates.all {
                it.value.isNotBlank() &&
                    it.weight.isFinite() &&
                    it.weight in 0.0..1.0
            }

    private fun roleOf(node: UiNodeSnapshot): String {
        val simpleName = node.className
            ?.substringAfterLast('.')
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return when {
            "button" in simpleName -> "button"
            "edittext" in simpleName -> "textfield"
            "checkbox" in simpleName -> "checkbox"
            "radiobutton" in simpleName -> "radio"
            "switch" in simpleName -> "switch"
            "image" in simpleName -> "image"
            "list" in simpleName || "recyclerview" in simpleName -> "list"
            "text" in simpleName -> "text"
            else -> simpleName
        }
    }

    private fun String?.normalized(): String =
        this?.trim()?.lowercase(Locale.ROOT).orEmpty()

    private data class ScoredNode(
        val path: List<Int>,
        val windowId: Int?,
        val layer: Int,
        val node: UiNodeSnapshot,
        val score: Double,
        val fingerprintScore: Double,
    )

    /**
     * 一个待搜索的根。
     *
     * 单窗口调用时 windowId 为 null、layer 为 0，与多窗口模型引入前完全一致，
     * 因此既有单根调用方（回放引擎、编辑器预演）无需改动。
     */
    private data class CandidateRoot(
        val windowId: Int?,
        val layer: Int,
        val root: UiNodeSnapshot,
    )

    private companion object {
        const val DEFAULT_MINIMUM_SCORE = 0.70
        const val DEFAULT_UNIQUENESS_MARGIN = 0.15
    }
}

object NodeFingerprint {
    fun create(node: UiNodeSnapshot): String {
        val canonical = listOf(
            node.packageName.orEmpty(),
            node.className.orEmpty(),
            node.resourceId.orEmpty(),
            node.state.clickable.toString(),
            node.state.editable.toString(),
            node.state.scrollable.toString(),
            node.children.size.toString(),
        ).joinToString(separator = "\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
