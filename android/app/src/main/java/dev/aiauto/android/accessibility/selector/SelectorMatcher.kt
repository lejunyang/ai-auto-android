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
import dev.aiauto.android.accessibility.model.SelectorMatchMode
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
                matchesText(node.contentDescription, expected, candidate.match)

            SelectorStrategy.TEXT -> matchesText(node.text, expected, candidate.match)
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

    /**
     * 按匹配方式比较 text / contentDescription。
     *
     * expected 已在调用处 normalized；这里对 actual 也 normalize 后再比，
     * 与精确匹配同口径（trim+lowercase）。只有这两个维度支持非精确匹配，
     * 标识符维度在别处始终精确相等。
     */
    private fun matchesText(
        actual: String?,
        expected: String,
        mode: SelectorMatchMode,
    ): Boolean {
        val normalizedActual = actual.normalized()
        return when (mode) {
            SelectorMatchMode.EXACT -> normalizedActual == expected
            SelectorMatchMode.CONTAINS -> normalizedActual.contains(expected)
            SelectorMatchMode.STARTS_WITH -> normalizedActual.startsWith(expected)
            SelectorMatchMode.ENDS_WITH -> normalizedActual.endsWith(expected)
            SelectorMatchMode.REGEX -> regexMatches(expected, normalizedActual)
        }
    }

    /**
     * 用带执行超时的正则匹配，兜底 ReDoS。
     *
     * java.util.regex 有回溯，灾难性 pattern（如 (a+)+$ 配长文本）会指数级耗时。
     * pattern 已在 parser 入口限长并编译校验，这里再加最后一道：把输入包成一个
     * 每次 charAt 都检查截止时间的 CharSequence。回溯引擎在字符上疯狂来回读取，
     * 超时后 charAt 抛出，匹配被打断并判为不匹配——而不是让线程挂起。
     *
     * 不新起线程：per-match 起线程开销大，且线程无法安全强杀；deadline-charAt
     * 是 java.util.regex ReDoS 的标准缓解手段，在当前线程内即可打断。
     */
    private fun regexMatches(pattern: String, actual: String): Boolean {
        val compiled = runCatching { java.util.regex.Pattern.compile(pattern) }
            .getOrNull() ?: return false
        val deadline = System.nanoTime() + REGEX_TIMEOUT_MS * 1_000_000L
        val guarded = DeadlineCharSequence(actual, deadline)
        return try {
            compiled.matcher(guarded).find()
        } catch (_: RegexTimeoutException) {
            // 超时视为不匹配：与「pattern 不成立」对调用方语义一致，且不挂起。
            false
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

        // regex 匹配的执行超时。取 100ms：正常 pattern 在几十字符文本上远低于此，
        // 灾难性回溯则会迅速撞上截止时间被打断。这是 ReDoS 的最后一道防线。
        const val REGEX_TIMEOUT_MS = 100L
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

// RegexTimeoutException 在正则匹配超过截止时间时抛出，用于打断灾难性回溯。
private class RegexTimeoutException : RuntimeException()

// DeadlineCharSequence 包装文本，在每次 charAt 检查截止时间。
//
// java.util.regex 的回溯引擎在匹配时大量读取字符；把 deadline 检查放在 charAt 上，
// 一旦超时就抛 RegexTimeoutException，从而在当前线程内打断匹配，无需新起线程或
// 强杀线程（后者在 JVM 上不安全）。这是 java.util.regex ReDoS 的标准缓解手段。
private class DeadlineCharSequence(
    private val delegate: CharSequence,
    private val deadlineNanos: Long,
) : CharSequence {
    override val length: Int get() = delegate.length

    override fun get(index: Int): Char {
        if (System.nanoTime() > deadlineNanos) {
            throw RegexTimeoutException()
        }
        return delegate[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineCharSequence(delegate.subSequence(startIndex, endIndex), deadlineNanos)

    override fun toString(): String = delegate.toString()
}