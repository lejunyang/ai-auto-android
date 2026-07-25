package dev.aiauto.android.automation.recording.webview

/**
 * 功能用途：编排 WebView 流程保存、fixture 复位、逐步语义观察、动作提交和后置验证。
 */

import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.automation.recording.ReplayGateway

/**
 * 通过可注入端口连接现有 `ReplayGateway`；任何上下文漂移都会停止后续动作。
 */
class WebViewReplayOrchestrator(
    private val adapter: WebViewSemanticAdapter,
    private val gateway: ReplayGateway,
    private val pageIdentity: WebViewPageIdentity,
    private val flowStore: WebViewFlowStore,
    private val resetPort: WebViewResetPort,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val observationTtlMs: Long = DEFAULT_OBSERVATION_TTL_MS,
    private val verifyObservationBeforeDispatch: Boolean = true,
) {
    init {
        require(observationTtlMs > 0L)
    }

    fun saveResetAndReplay(flow: WebViewRecordedFlow): WebViewReplayReport {
        if (!flowStore.save(flow)) {
            return failure(
                code = WebViewSemanticErrorCode.SAVE_FAILED,
                message = "The WebView recorded flow could not be saved",
            )
        }
        if (!resetPort.reset(flow.targetPackage)) {
            return failure(
                code = WebViewSemanticErrorCode.RESET_FAILED,
                message = "The WebView fixture could not be reset",
            )
        }

        val results = mutableListOf<WebViewReplayStepResult>()
        var currentSnapshot: UiNodeSnapshot? = null
        flow.steps.forEach { step ->
            val preAction = currentSnapshot ?: when (
                val snapshot = gateway.snapshot(flow.targetPackage)
            ) {
                is AccessibilityResult.Failure -> return failure(
                    code = WebViewSemanticErrorCode.SNAPSHOT_FAILED,
                    message = snapshot.error.message,
                    steps = results,
                )

                is AccessibilityResult.Success -> snapshot.value
            }
            val observation = observe(preAction)
            if (observation.runtime != flow.runtime) {
                return failure(
                    code = WebViewSemanticErrorCode.RUNTIME_DRIFT,
                    level = WebViewCompatibilityLevel.UNSUPPORTED,
                    message = "The API or WebView runtime changed after recording",
                    steps = results,
                )
            }
            if (!pageIdentity.containsState(preAction, step.expectedState)) {
                return failure(
                    code = WebViewSemanticErrorCode.PRECONDITION_FAILED,
                    level = WebViewCompatibilityLevel.UNSUPPORTED,
                    message = "The WebView step precondition is not present",
                    steps = results,
                )
            }

            val plan = when (
                val planned = adapter.plan(
                    observation = observation,
                    expectedPackage = flow.targetPackage,
                    expectedPageId = step.expectedPageId,
                    intent = step.intent,
                )
            ) {
                is WebViewSemanticPlanResult.Rejected -> return failure(
                    code = planned.code,
                    level = planned.level,
                    message = planned.message,
                    steps = results,
                )

                is WebViewSemanticPlanResult.Ready -> planned.plan
            }

            if (verifyObservationBeforeDispatch) {
                val refreshed = when (val snapshot = gateway.snapshot(flow.targetPackage)) {
                    is AccessibilityResult.Failure -> return failure(
                        code = WebViewSemanticErrorCode.SNAPSHOT_FAILED,
                        message = snapshot.error.message,
                        steps = results,
                    )

                    is AccessibilityResult.Success -> snapshot.value
                }
                if (
                    refreshed != preAction ||
                    pageIdentity.identify(refreshed) != observation.pageId ||
                    pageIdentity.runtime(refreshed) != observation.runtime
                ) {
                    return failure(
                        code = WebViewSemanticErrorCode.OBSERVATION_STALE,
                        level = WebViewCompatibilityLevel.UNSUPPORTED,
                        message = "The WebView semantic observation changed before dispatch",
                        steps = results,
                    )
                }
            }

            val execution = when (val executed = gateway.execute(plan.action)) {
                is AccessibilityResult.Failure -> return failure(
                    code = WebViewSemanticErrorCode.ACTION_FAILED,
                    message = executed.error.message,
                    steps = results,
                )

                is AccessibilityResult.Success -> executed.value
            }
            val postAction = when (val snapshot = gateway.snapshot(flow.targetPackage)) {
                is AccessibilityResult.Failure -> return failure(
                    code = WebViewSemanticErrorCode.SNAPSHOT_FAILED,
                    message = snapshot.error.message,
                    steps = results,
                )

                is AccessibilityResult.Success -> snapshot.value
            }
            if (
                postAction.packageName != flow.targetPackage ||
                pageIdentity.runtime(postAction) != flow.runtime ||
                pageIdentity.identify(postAction) != step.postcondition.pageId ||
                !pageIdentity.containsState(postAction, step.postcondition.state)
            ) {
                return failure(
                    code = WebViewSemanticErrorCode.POSTCONDITION_FAILED,
                    level = WebViewCompatibilityLevel.UNSUPPORTED,
                    message = "The WebView step postcondition could not be verified",
                    steps = results,
                )
            }
            results += WebViewReplayStepResult(
                stepId = step.id,
                route = execution.route.name,
            )
            currentSnapshot = postAction
        }
        return WebViewReplayReport(succeeded = true, steps = results)
    }

    private fun observe(root: UiNodeSnapshot): WebViewSemanticObservation {
        val capturedAtMs = nowMs()
        return WebViewSemanticObservation(
            observationId = "webview-$capturedAtMs-${root.hashCode()}",
            runtime = pageIdentity.runtime(root),
            packageName = root.packageName.orEmpty(),
            pageId = pageIdentity.identify(root).orEmpty(),
            root = root,
            visualSurfaceAvailable = true,
            postconditionsVerifiable = true,
            restricted = false,
            capturedAtMs = capturedAtMs,
            expiresAtMs = capturedAtMs + observationTtlMs,
        )
    }

    private fun failure(
        code: WebViewSemanticErrorCode,
        message: String,
        steps: List<WebViewReplayStepResult> = emptyList(),
        level: WebViewCompatibilityLevel? = null,
    ) = WebViewReplayReport(
        succeeded = false,
        steps = steps.toList(),
        errorCode = code,
        level = level,
        message = message,
    )

    private companion object {
        const val DEFAULT_OBSERVATION_TTL_MS = 2_000L
    }
}
