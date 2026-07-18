package dev.aiauto.android.automation.recording

import dev.aiauto.android.accessibility.AccessibilityRuntime
import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityError
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.selector.SelectorMatch
import dev.aiauto.android.accessibility.selector.SelectorMatcher
import dev.aiauto.android.bridge.AccessibilityCommandJsonParser
import dev.aiauto.android.bridge.BridgeException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

interface ReplayGateway {
    fun snapshot(expectedPackage: String?): AccessibilityResult<UiNodeSnapshot>

    fun execute(action: RecordedAction): AccessibilityResult<ActionExecution>
}

interface SecretResolver {
    fun resolve(alias: String): String?
}

interface ReplayTime {
    fun nowMs(): Long

    fun sleep(ms: Long)
}

object SystemReplayTime : ReplayTime {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun sleep(ms: Long) = Thread.sleep(ms)
}

class AndroidReplayGateway(
    private val commandParser: AccessibilityCommandJsonParser =
        AccessibilityCommandJsonParser(),
) : ReplayGateway {
    override fun snapshot(expectedPackage: String?): AccessibilityResult<UiNodeSnapshot> =
        AccessibilityRuntime.snapshot(expectedPackage)

    override fun execute(action: RecordedAction): AccessibilityResult<ActionExecution> {
        val command = try {
            commandParser.parse(
                buildJsonObject {
                    put("type", JsonPrimitive(action.type))
                    put("params", action.params)
                },
            )
        } catch (error: BridgeException) {
            return AccessibilityResult.Failure(
                code = AccessibilityErrorCode.INVALID_ACTION,
                message = error.message,
            )
        }
        return AccessibilityRuntime.execute(command)
    }
}

class ConditionWaiter(
    private val gateway: ReplayGateway,
    private val selectorMatcher: SelectorMatcher = SelectorMatcher(),
    private val time: ReplayTime = SystemReplayTime,
    private val pollIntervalMs: Long = 100,
) {
    fun await(
        predicate: RecordedPredicate,
        expectedPackage: String?,
    ): Boolean {
        val deadline = time.nowMs() + predicate.timeoutMs
        var stableSince: Long? = null
        var previousFingerprint: String? = null
        do {
            val snapshot = gateway.snapshot(expectedPackage)
            val root = (snapshot as? AccessibilityResult.Success)?.value
            val satisfied = if (root == null) {
                predicate.operator == "notExists"
            } else {
                when (predicate.kind) {
                    "node", "text" -> evaluateNode(root, predicate)
                    "package", "window" -> evaluatePackage(root, predicate)
                    "uiStable" -> {
                        val current = snapshotFingerprint(root)
                        if (current == previousFingerprint) {
                            stableSince = stableSince ?: time.nowMs()
                        } else {
                            stableSince = null
                        }
                        previousFingerprint = current
                        val duration = predicate.stableDurationMs ?: DEFAULT_STABLE_DURATION_MS
                        stableSince?.let { time.nowMs() - it >= duration } == true
                    }

                    else -> false
                }
            }
            if (satisfied) {
                return true
            }
            if (time.nowMs() >= deadline) {
                return false
            }
            time.sleep(minOf(pollIntervalMs, deadline - time.nowMs()))
        } while (time.nowMs() <= deadline)
        return false
    }

    private fun evaluateNode(
        root: UiNodeSnapshot,
        predicate: RecordedPredicate,
    ): Boolean {
        val target = predicate.target?.let(::targetFromJson) ?: return false
        val match = selectorMatcher.match(root, target)
        val found = match is SelectorMatch.Found
        return when (predicate.operator) {
            "exists" -> found
            "notExists" -> !found
            "equals", "contains", "matches" -> {
                val node = (match as? SelectorMatch.Found)?.node ?: return false
                val actual = node.text.orEmpty()
                val expected = predicate.expected?.jsonPrimitive?.contentOrNull.orEmpty()
                when (predicate.operator) {
                    "equals" -> actual == expected
                    "contains" -> expected in actual
                    "matches" -> runCatching { Regex(expected).containsMatchIn(actual) }
                        .getOrDefault(false)

                    else -> false
                }
            }

            else -> false
        }
    }

    private fun evaluatePackage(
        root: UiNodeSnapshot,
        predicate: RecordedPredicate,
    ): Boolean {
        val actual = root.packageName.orEmpty()
        val expected = predicate.expected?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (predicate.operator) {
            "equals" -> actual == expected
            "contains" -> expected in actual
            "matches" -> runCatching { Regex(expected).matches(actual) }.getOrDefault(false)
            else -> false
        }
    }

    private fun snapshotFingerprint(root: UiNodeSnapshot): String =
        buildString {
            fun appendNode(node: UiNodeSnapshot) {
                append(node.packageName)
                append('|')
                append(node.className)
                append('|')
                append(node.resourceId)
                append('|')
                append(node.text)
                append(';')
                node.children.forEach(::appendNode)
            }
            appendNode(root)
        }

    private companion object {
        const val DEFAULT_STABLE_DURATION_MS = 300L
    }
}

class ReplayEngine(
    private val gateway: ReplayGateway,
    private val secretResolver: SecretResolver,
    private val selectorMatcher: SelectorMatcher = SelectorMatcher(),
    private val time: ReplayTime = SystemReplayTime,
    private val waiter: ConditionWaiter = ConditionWaiter(
        gateway = gateway,
        selectorMatcher = selectorMatcher,
        time = time,
    ),
) {
    fun replay(
        script: AutomationScript,
        secrets: Map<String, String> = emptyMap(),
    ): ReplayReport {
        require(script.schemaVersion == RECORDING_SCHEMA_VERSION)
        val startedAt = time.nowMs()
        val results = mutableListOf<ReplayStepResult>()
        var requiresIntervention = false

        for (step in script.steps) {
            val result = replayStep(step, script.targetPackages, secrets)
            results += result
            if (result.status == ReplayStepStatus.FAILED) {
                requiresIntervention = step.failurePolicy == "requestIntervention"
                break
            }
        }
        return ReplayReport(
            scriptId = script.id,
            startedAtMs = startedAt,
            finishedAtMs = time.nowMs(),
            succeeded = results.size == script.steps.size &&
                results.all { it.status == ReplayStepStatus.SUCCEEDED },
            requiresIntervention = requiresIntervention,
            steps = results,
        )
    }

    private fun replayStep(
        step: RecordedStep,
        targetPackages: List<String>,
        secrets: Map<String, String>,
    ): ReplayStepResult {
        val resolved = resolveSecret(step.action, secrets) ?: return failure(
            step = step,
            attempts = 0,
            code = "SECRET_REQUIRED",
            message = "A required secret value was not provided",
        )
        if (resolved.type == "ui.wait" || resolved.type == "ui.assert") {
            val predicate = resolved.toPredicate() ?: return failure(
                step = step,
                attempts = 0,
                code = "INVALID_PREDICATE",
                message = "The recorded condition is invalid",
            )
            val matched = waiter.await(predicate, targetPackages.singleOrNull())
            return if (matched) {
                ReplayStepResult(
                    stepId = step.id,
                    status = ReplayStepStatus.SUCCEEDED,
                    attempts = 1,
                )
            } else {
                failure(
                    step = step,
                    attempts = 1,
                    code = "CONDITION_TIMEOUT",
                    message = "The recorded condition did not become true",
                )
            }
        }
        val expectedPackage = resolved.params["target"]
            ?.let { it as? JsonObject }
            ?.get("packageName")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: targetPackages.singleOrNull()

        semanticTarget(resolved)?.let { target ->
            val snapshot = gateway.snapshot(expectedPackage)
            val root = (snapshot as? AccessibilityResult.Success)?.value
                ?: return failure(
                    step = step,
                    attempts = 0,
                    code = (snapshot as AccessibilityResult.Failure).error.code.name,
                    message = snapshot.error.message,
                )
            when (val match = selectorMatcher.match(root, target)) {
                is SelectorMatch.NotFound -> return failure(
                    step,
                    0,
                    "SELECTOR_LOW_CONFIDENCE",
                    "No semantic selector reached the minimum confidence",
                )

                is SelectorMatch.Ambiguous -> return failure(
                    step,
                    0,
                    "SELECTOR_AMBIGUOUS",
                    "Multiple semantic targets matched with similar confidence",
                )

                is SelectorMatch.Found -> Unit
            }
        }

        var lastFailure: AccessibilityError? = null
        repeat(step.retry.maxAttempts) { index ->
            val attempts = index + 1
            when (val execution = gateway.execute(resolved)) {
                is AccessibilityResult.Success -> {
                    val waitSucceeded = step.waitAfter?.let {
                        waiter.await(it, expectedPackage)
                    } ?: true
                    if (waitSucceeded) {
                        return ReplayStepResult(
                            stepId = step.id,
                            status = ReplayStepStatus.SUCCEEDED,
                            attempts = attempts,
                            route = execution.value.route.name,
                            matchScore = execution.value.matchScore,
                        )
                    }
                    lastFailure = AccessibilityError(
                        code = AccessibilityErrorCode.ACTION_FAILED,
                        message = "The post-action condition timed out",
                        retryable = true,
                    )
                }

                is AccessibilityResult.Failure -> lastFailure = execution.error
            }
            if (attempts < step.retry.maxAttempts) {
                time.sleep(step.retry.backoffMs)
            }
        }

        return failure(
            step = step,
            attempts = step.retry.maxAttempts,
            code = lastFailure?.code?.name ?: "ACTION_FAILED",
            message = lastFailure?.message ?: "The recorded action failed",
        )
    }

    private fun resolveSecret(
        action: RecordedAction,
        secrets: Map<String, String>,
    ): RecordedAction? {
        if (action.type != "ui.setText") {
            return action
        }
        val alias = action.params["secretRef"]?.jsonPrimitive?.contentOrNull ?: return action
        val value = secrets[alias] ?: secretResolver.resolve(alias) ?: return null
        return action.copy(
            params = buildJsonObject {
                action.params.forEach { (key, element) ->
                    if (key != "secretRef") {
                        put(key, element)
                    }
                }
                put("text", JsonPrimitive(value))
            },
        )
    }

    private fun semanticTarget(action: RecordedAction): NodeTarget? {
        if (action.type !in SEMANTIC_ACTION_TYPES) {
            return null
        }
        val rawTarget = action.params["target"] as? JsonObject ?: return null
        return targetFromJson(rawTarget)
    }

    private fun RecordedAction.toPredicate(): RecordedPredicate? {
        val kind = params["kind"]?.jsonPrimitive?.contentOrNull ?: return null
        val operator = params["operator"]?.jsonPrimitive?.contentOrNull ?: return null
        return RecordedPredicate(
            kind = kind,
            operator = operator,
            target = params["target"] as? JsonObject,
            expected = params["expected"],
            stableDurationMs = params["stableDurationMs"]?.jsonPrimitive?.longOrNull,
            timeoutMs = params["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 5_000,
        )
    }

    private fun failure(
        step: RecordedStep,
        attempts: Int,
        code: String,
        message: String,
    ): ReplayStepResult = ReplayStepResult(
        stepId = step.id,
        status = ReplayStepStatus.FAILED,
        attempts = attempts,
        errorCode = code,
        message = message,
    )

    private companion object {
        val SEMANTIC_ACTION_TYPES = setOf(
            "ui.click",
            "ui.longClick",
            "ui.setText",
            "ui.scroll",
        )
    }
}

private fun targetFromJson(value: JsonObject): NodeTarget {
    val action = RecordedAction(
        type = "ui.click",
        params = buildJsonObject { put("target", value) },
    )
    return when (
        val command = try {
            AccessibilityCommandJsonParser().parse(
                buildJsonObject {
                    put("type", JsonPrimitive(action.type))
                    put("params", action.params)
                },
            )
        } catch (_: BridgeException) {
            null
        }
    ) {
        is AccessibilityCommand.Click -> command.target
        else -> NodeTarget()
    }
}
