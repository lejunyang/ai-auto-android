package dev.aiauto.android.automation.recording.webview

/**
 * 功能用途：只用 Accessibility 虚拟节点评估 WebView 能力并生成现有类型化语义动作。
 */

import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.ScrollDirection
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorStrategy
import dev.aiauto.android.accessibility.selector.SelectorMatch
import dev.aiauto.android.accessibility.selector.SelectorMatcher
import dev.aiauto.android.automation.recording.RecordedAction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** 评估页面关键虚拟节点并输出四级兼容结果。 */
class WebViewCapabilityEvaluator(
    private val selectorMatcher: SelectorMatcher = SelectorMatcher(),
) {
    fun evaluate(
        observation: WebViewSemanticObservation,
        contract: WebViewPageContract,
    ): WebViewCapabilityMatrixEntry {
        val available = contract.requirements
            .filter { requirement -> requirement.availableIn(observation) }
            .mapTo(linkedSetOf(), WebViewSemanticRequirement::capability)
        val required = contract.requirements
            .mapTo(linkedSetOf(), WebViewSemanticRequirement::capability)
        val missing = required - available
        val reasons = buildSet {
            if (observation.restricted) {
                add("RESTRICTED_CONTENT")
            }
            if (!observation.postconditionsVerifiable) {
                add("POSTCONDITION_UNVERIFIABLE")
            }
            if (contract.pageId != observation.pageId) {
                add("PAGE_MISMATCH")
            }
            missing.forEach { add("MISSING_${it.name}") }
        }
        val semanticAvailable = available.isNotEmpty()
        val hasVisualRegion = contract.visualRegionCount > 0 &&
            observation.visualSurfaceAvailable
        val level = when {
            observation.restricted || !observation.postconditionsVerifiable ||
                contract.pageId != observation.pageId ->
                WebViewCompatibilityLevel.UNSUPPORTED

            missing.isEmpty() && !hasVisualRegion ->
                WebViewCompatibilityLevel.FULL_SEMANTIC

            semanticAvailable ->
                WebViewCompatibilityLevel.HYBRID

            hasVisualRegion ->
                WebViewCompatibilityLevel.VISUAL_ONLY

            else ->
                WebViewCompatibilityLevel.UNSUPPORTED
        }
        return WebViewCapabilityMatrixEntry(
            runtime = observation.runtime,
            pageId = observation.pageId,
            level = level,
            availableCapabilities = available,
            missingCapabilities = missing,
            reasons = reasons,
        )
    }

    private fun WebViewSemanticRequirement.availableIn(
        observation: WebViewSemanticObservation,
    ): Boolean = when (val match = selectorMatcher.match(observation.root, target)) {
        is SelectorMatch.Found -> requiredAction in match.node.actions
        is SelectorMatch.Ambiguous, is SelectorMatch.NotFound -> false
    }
}

/**
 * 将已验证的虚拟节点意图转换为 `RecordedAction`，不暴露 tap/swipe 或坐标回退。
 */
class WebViewSemanticAdapter(
    private val selectorMatcher: SelectorMatcher = SelectorMatcher(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun plan(
        observation: WebViewSemanticObservation,
        expectedPackage: String,
        expectedPageId: String,
        intent: WebViewSemanticIntent,
    ): WebViewSemanticPlanResult {
        val contextFailure = validateContext(
            observation = observation,
            expectedPackage = expectedPackage,
            expectedPageId = expectedPageId,
        )
        if (contextFailure != null) {
            return contextFailure
        }
        if (intent == WebViewSemanticIntent.Back) {
            return ready(observation, RecordedAction("ui.back", JsonObject(emptyMap())))
        }

        val target = intent.target()
        val requiredAction = intent.requiredAction()
        return when (val match = selectorMatcher.match(observation.root, target)) {
            is SelectorMatch.Ambiguous -> rejected(
                code = WebViewSemanticErrorCode.SELECTOR_AMBIGUOUS,
                level = WebViewCompatibilityLevel.HYBRID,
                message = "Multiple WebView virtual nodes matched the semantic target",
            )

            is SelectorMatch.NotFound -> rejected(
                code = WebViewSemanticErrorCode.SEMANTIC_UNAVAILABLE,
                level = semanticUnavailableLevel(observation),
                message = "The required WebView virtual node is unavailable",
            )

            is SelectorMatch.Found -> {
                if (requiredAction !in match.node.actions) {
                    rejected(
                        code = WebViewSemanticErrorCode.SEMANTIC_UNAVAILABLE,
                        level = semanticUnavailableLevel(observation),
                        message = "The WebView virtual node does not expose the required action",
                    )
                } else {
                    ready(observation, intent.toRecordedAction())
                }
            }
        }
    }

    private fun validateContext(
        observation: WebViewSemanticObservation,
        expectedPackage: String,
        expectedPageId: String,
    ): WebViewSemanticPlanResult.Rejected? = when {
        observation.expiresAtMs <= nowMs() ||
            observation.capturedAtMs > nowMs() ->
            rejected(
                WebViewSemanticErrorCode.OBSERVATION_EXPIRED,
                WebViewCompatibilityLevel.UNSUPPORTED,
                "The WebView semantic observation has expired",
            )

        observation.capturedAtMs > observation.expiresAtMs ->
            rejected(
                WebViewSemanticErrorCode.OBSERVATION_EXPIRED,
                WebViewCompatibilityLevel.UNSUPPORTED,
                "The WebView semantic observation has an invalid validity window",
            )

        observation.packageName != expectedPackage ||
            observation.root.packageName != expectedPackage ->
            rejected(
                WebViewSemanticErrorCode.PACKAGE_DRIFT,
                WebViewCompatibilityLevel.UNSUPPORTED,
                "The foreground package changed after the flow was recorded",
            )

        observation.pageId != expectedPageId ->
            rejected(
                WebViewSemanticErrorCode.PAGE_DRIFT,
                WebViewCompatibilityLevel.UNSUPPORTED,
                "The WebView page changed before semantic planning",
            )

        observation.restricted || !observation.postconditionsVerifiable ->
            rejected(
                WebViewSemanticErrorCode.SEMANTIC_UNAVAILABLE,
                WebViewCompatibilityLevel.UNSUPPORTED,
                "The page cannot provide a safe verifiable semantic action",
            )

        else -> null
    }

    private fun ready(
        observation: WebViewSemanticObservation,
        action: RecordedAction,
    ) = WebViewSemanticPlanResult.Ready(
        plan = WebViewSemanticPlan(
            observationId = observation.observationId,
            action = action,
        ),
        level = WebViewCompatibilityLevel.FULL_SEMANTIC,
    )

    private fun rejected(
        code: WebViewSemanticErrorCode,
        level: WebViewCompatibilityLevel,
        message: String,
    ) = WebViewSemanticPlanResult.Rejected(code, level, message)

    private fun semanticUnavailableLevel(
        observation: WebViewSemanticObservation,
    ): WebViewCompatibilityLevel = when {
        observation.restricted || !observation.postconditionsVerifiable ->
            WebViewCompatibilityLevel.UNSUPPORTED

        observation.visualSurfaceAvailable ->
            WebViewCompatibilityLevel.HYBRID

        else ->
            WebViewCompatibilityLevel.UNSUPPORTED
    }

    private fun WebViewSemanticIntent.target(): NodeTarget = when (this) {
        is WebViewSemanticIntent.Click -> target
        is WebViewSemanticIntent.SetText -> target
        is WebViewSemanticIntent.LongClick -> target
        is WebViewSemanticIntent.Scroll -> target
        is WebViewSemanticIntent.Navigate -> target
        WebViewSemanticIntent.Back -> error("Back does not have a semantic target")
    }

    private fun WebViewSemanticIntent.requiredAction(): NodeAction = when (this) {
        is WebViewSemanticIntent.Click, is WebViewSemanticIntent.Navigate ->
            NodeAction.CLICK

        is WebViewSemanticIntent.SetText ->
            NodeAction.SET_TEXT

        is WebViewSemanticIntent.LongClick ->
            NodeAction.LONG_CLICK

        is WebViewSemanticIntent.Scroll -> when (direction) {
            ScrollDirection.UP -> NodeAction.SCROLL_UP
            ScrollDirection.DOWN -> NodeAction.SCROLL_DOWN
            ScrollDirection.LEFT -> NodeAction.SCROLL_LEFT
            ScrollDirection.RIGHT -> NodeAction.SCROLL_RIGHT
            ScrollDirection.FORWARD -> NodeAction.SCROLL_FORWARD
            ScrollDirection.BACKWARD -> NodeAction.SCROLL_BACKWARD
        }

        WebViewSemanticIntent.Back ->
            error("Back does not require a node action")
    }

    private fun WebViewSemanticIntent.toRecordedAction(): RecordedAction = when (this) {
        is WebViewSemanticIntent.Click, is WebViewSemanticIntent.Navigate ->
            RecordedAction(
                type = "ui.click",
                params = buildJsonObject { put("target", target().toJson()) },
            )

        is WebViewSemanticIntent.SetText ->
            RecordedAction(
                type = "ui.setText",
                params = buildJsonObject {
                    put("target", target.toJson())
                    put("text", JsonPrimitive(text))
                },
            )

        is WebViewSemanticIntent.LongClick ->
            RecordedAction(
                type = "ui.longClick",
                params = buildJsonObject {
                    put("target", target.toJson())
                    put("durationMs", JsonPrimitive(durationMs))
                },
            )

        is WebViewSemanticIntent.Scroll ->
            RecordedAction(
                type = "ui.scroll",
                params = buildJsonObject {
                    put("target", target.toJson())
                    put("direction", JsonPrimitive(direction.name.lowercase()))
                },
            )

        WebViewSemanticIntent.Back ->
            RecordedAction("ui.back", JsonObject(emptyMap()))
    }
}

private fun NodeTarget.toJson(): JsonObject = buildJsonObject {
    packageName?.let { put("packageName", JsonPrimitive(it)) }
    put(
        "selectorCandidates",
        buildJsonArray {
            selectorCandidates.forEach { candidate -> add(candidate.toJson()) }
        },
    )
    if (fingerprint.isNotEmpty()) {
        put(
            "fingerprint",
            buildJsonObject {
                fingerprint.forEach { (key, value) ->
                    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
                }
            },
        )
    }
}

private fun SelectorCandidate.toJson(): JsonObject = buildJsonObject {
    put("strategy", JsonPrimitive(strategy.protocolName()))
    put("value", JsonPrimitive(value))
    put("weight", JsonPrimitive(weight))
    if (required) {
        put("required", JsonPrimitive(true))
    }
}

private fun SelectorStrategy.protocolName(): String = when (this) {
    SelectorStrategy.RESOURCE_ID -> "resourceId"
    SelectorStrategy.CONTENT_DESCRIPTION -> "contentDescription"
    SelectorStrategy.TEXT -> "text"
    SelectorStrategy.ROLE -> "role"
    SelectorStrategy.ANCESTOR -> "ancestor"
    SelectorStrategy.FINGERPRINT -> "fingerprint"
}
