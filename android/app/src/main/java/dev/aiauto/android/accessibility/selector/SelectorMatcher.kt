package dev.aiauto.android.accessibility.selector

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

import dev.aiauto.android.accessibility.model.NodePath
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorStrategy
import dev.aiauto.android.accessibility.model.UiNodeSnapshot

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

    fun match(root: UiNodeSnapshot, target: NodeTarget): SelectorMatch {
        if (!validCandidates(target.selectorCandidates)) {
            return SelectorMatch.NotFound()
        }

        val scoredNodes = buildList {
            visit(
                node = root,
                path = emptyList(),
                ancestors = emptyList(),
                target = target,
                destination = this,
            )
        }.sortedWith(
            compareByDescending<ScoredNode> { it.score }
                .thenByDescending { it.fingerprintScore },
        )

        val best = scoredNodes.firstOrNull() ?: return SelectorMatch.NotFound()
        if (best.score < minimumScore) {
            return SelectorMatch.NotFound(bestScore = best.score)
        }

        val competing = scoredNodes.drop(1)
            .takeWhile { best.score - it.score < uniquenessMargin }
            .filter { it.score >= minimumScore }
        if (competing.isNotEmpty() && !fingerprintUniquelySeparates(best, competing)) {
            return SelectorMatch.Ambiguous(
                score = best.score,
                candidateCount = competing.size + 1,
            )
        }

        return SelectorMatch.Found(
            path = NodePath(best.path),
            node = best.node,
            score = best.score,
        )
    }

    private fun visit(
        node: UiNodeSnapshot,
        path: List<Int>,
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
        val node: UiNodeSnapshot,
        val score: Double,
        val fingerprintScore: Double,
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
