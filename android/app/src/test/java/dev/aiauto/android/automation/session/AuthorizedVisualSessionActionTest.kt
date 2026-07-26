package dev.aiauto.android.automation.session

/**
 * 测试用途：验证授权视觉 action 绑定 observation、目标包、N45 坐标门和三阶段图片清理。
 */

import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.ActionRoute
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import dev.aiauto.android.automation.recording.replay.visual.ExplicitVisualAction
import dev.aiauto.android.automation.recording.replay.visual.ReplayInsets
import dev.aiauto.android.automation.recording.replay.visual.VisualReplayScreen
import dev.aiauto.android.observe.visual.NormalizedBounds
import dev.aiauto.android.observe.visual.NormalizedPoint
import dev.aiauto.android.observe.visual.PixelBounds
import dev.aiauto.android.observe.visual.TrustedVisualImageVerifier
import dev.aiauto.android.observe.visual.VisualObservationCapture
import dev.aiauto.android.observe.visual.VisualObservationStore
import dev.aiauto.android.observe.visual.VisualResult
import dev.aiauto.android.observe.visual.VisualScreen
import dev.aiauto.android.provider.ProviderAction
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedVisualSessionActionTest {
    @Test
    fun `authorized tap validates fresh image executes target bound point and clears every lease BitsUT`() =
        runTest {
            val sourceHierarchy = hierarchy("ready")
            val postHierarchy = hierarchy("changed")
            val source = lease(SOURCE_ID, "2026-07-26T02:00:00Z", pngBytes(1))
            val pre = lease(PRE_ID, "2026-07-26T02:00:01Z", pngBytes(1))
            val post = lease(POST_ID, "2026-07-26T02:00:02Z", pngBytes(2))
            val refreshed = ArrayDeque(listOf(pre, post))
            val actions = mutableListOf<Pair<ExplicitVisualAction, String>>()
            val context = context(
                sourceHierarchy = sourceHierarchy,
                source = source,
                refresh = { refreshed.removeFirst() },
                execute = { action, targetPackage ->
                    actions += action to targetPackage
                    AccessibilityResult.Success(
                        ActionExecution(route = ActionRoute.SCREEN_GESTURE),
                    )
                },
            )

            context.validateBefore(
                SessionObservation(TARGET_PACKAGE, "ready", sourceHierarchy),
                TARGET_PACKAGE,
            )
            val execution = context.execute(TARGET_PACKAGE)
            context.verifyAfter(
                SessionObservation(TARGET_PACKAGE, "changed", postHierarchy),
                TARGET_PACKAGE,
            )
            context.close()

            assertEquals(
                listOf(
                    ExplicitVisualAction.Tap(ScreenPoint(50, 150)) to TARGET_PACKAGE,
                ),
                actions,
            )
            assertTrue(execution.summary.contains("authorized visual"))
            assertFalse(source.hasObservation())
            assertFalse(pre.hasObservation())
            assertFalse(post.hasObservation())
        }

    @Test
    fun `authorized swipe maps candidate relative endpoints and preserves target package BitsUT`() =
        runTest {
            val sourceHierarchy = hierarchy("ready")
            val postHierarchy = hierarchy("changed")
            val source = lease(SOURCE_ID, "2026-07-26T02:00:00Z", pngBytes(1))
            val pre = lease(PRE_ID, "2026-07-26T02:00:01Z", pngBytes(1))
            val post = lease(POST_ID, "2026-07-26T02:00:02Z", pngBytes(2))
            val refreshed = ArrayDeque(listOf(pre, post))
            val actions = mutableListOf<Pair<ExplicitVisualAction, String>>()
            val action = visualSwipe()
            val context = context(
                sourceHierarchy = sourceHierarchy,
                source = source,
                refresh = { refreshed.removeFirst() },
                execute = { visual, targetPackage ->
                    actions += visual to targetPackage
                    AccessibilityResult.Success(
                        ActionExecution(route = ActionRoute.SCREEN_GESTURE),
                    )
                },
                action = action,
            )

            context.validateBefore(
                SessionObservation(TARGET_PACKAGE, "ready", sourceHierarchy),
                TARGET_PACKAGE,
            )
            context.execute(TARGET_PACKAGE)
            context.verifyAfter(
                SessionObservation(TARGET_PACKAGE, "changed", postHierarchy),
                TARGET_PACKAGE,
            )
            context.close()

            assertEquals(
                listOf(
                    ExplicitVisualAction.Swipe(
                        start = ScreenPoint(40, 140),
                        end = ScreenPoint(60, 160),
                        durationMs = 450,
                    ) to TARGET_PACKAGE,
                ),
                actions,
            )
            assertFalse(source.hasObservation())
            assertFalse(pre.hasObservation())
            assertFalse(post.hasObservation())
        }

    @Test
    fun `pre execution screenshot drift rejects with zero action and clears source plus pre BitsUT`() =
        runTest {
            val sourceHierarchy = hierarchy("ready")
            val source = lease(SOURCE_ID, "2026-07-26T02:00:00Z", pngBytes(1))
            val pre = lease(PRE_ID, "2026-07-26T02:00:01Z", pngBytes(9))
            var actionCalls = 0
            val context = context(
                sourceHierarchy = sourceHierarchy,
                source = source,
                refresh = { pre },
                execute = { _, _ ->
                    actionCalls += 1
                    AccessibilityResult.Success(
                        ActionExecution(route = ActionRoute.SCREEN_GESTURE),
                    )
                },
            )

            val error = assertThrows(SessionFailureException::class.java) {
                kotlinx.coroutines.test.runTest {
                    context.validateBefore(
                        SessionObservation(TARGET_PACKAGE, "ready", sourceHierarchy),
                        TARGET_PACKAGE,
                    )
                }
            }
            context.close()

            assertTrue(error.message.orEmpty().contains("screenshot changed"))
            assertEquals(0, actionCalls)
            assertFalse(source.hasObservation())
            assertFalse(pre.hasObservation())
        }

    @Test
    fun `post verification failure occurs after one commit and context cannot execute twice BitsUT`() =
        runTest {
            val sourceHierarchy = hierarchy("ready")
            val source = lease(SOURCE_ID, "2026-07-26T02:00:00Z", pngBytes(1))
            val pre = lease(PRE_ID, "2026-07-26T02:00:01Z", pngBytes(1))
            val unchangedPost = lease(POST_ID, "2026-07-26T02:00:02Z", pngBytes(1))
            val refreshed = ArrayDeque(listOf(pre, unchangedPost))
            var actionCalls = 0
            val context = context(
                sourceHierarchy = sourceHierarchy,
                source = source,
                refresh = { refreshed.removeFirst() },
                execute = { _, _ ->
                    actionCalls += 1
                    AccessibilityResult.Success(
                        ActionExecution(route = ActionRoute.SCREEN_GESTURE),
                    )
                },
            )

            context.validateBefore(
                SessionObservation(TARGET_PACKAGE, "ready", sourceHierarchy),
                TARGET_PACKAGE,
            )
            context.execute(TARGET_PACKAGE)
            val verificationError = try {
                context.verifyAfter(
                    SessionObservation(TARGET_PACKAGE, "ready", sourceHierarchy),
                    TARGET_PACKAGE,
                )
                null
            } catch (error: SessionFailureException) {
                error
            }
            val secondExecution = try {
                context.execute(TARGET_PACKAGE)
                null
            } catch (error: SessionFailureException) {
                error
            }
            context.close()

            assertTrue(verificationError?.message.orEmpty().contains("after one commit"))
            assertTrue(secondExecution?.message.orEmpty().contains("already consumed"))
            assertEquals(1, actionCalls)
            assertFalse(source.hasObservation())
            assertFalse(pre.hasObservation())
            assertFalse(unchangedPost.hasObservation())
        }

    @Test
    fun `typed action rejection remains zero commit and closes leases BitsUT`() = runTest {
        val sourceHierarchy = hierarchy("ready")
        val source = lease(SOURCE_ID, "2026-07-26T02:00:00Z", pngBytes(1))
        val pre = lease(PRE_ID, "2026-07-26T02:00:01Z", pngBytes(1))
        val context = context(
            sourceHierarchy = sourceHierarchy,
            source = source,
            refresh = { pre },
            execute = { _, _ ->
                AccessibilityResult.Failure(
                    code = AccessibilityErrorCode.GESTURE_FAILED,
                    message = "rejected",
                )
            },
        )

        context.validateBefore(
            SessionObservation(TARGET_PACKAGE, "ready", sourceHierarchy),
            TARGET_PACKAGE,
        )
        val error = try {
            context.execute(TARGET_PACKAGE)
            null
        } catch (failure: SessionFailureException) {
            failure
        }
        context.close()

        assertTrue(error?.message.orEmpty().contains("before commit"))
        assertFalse(source.hasObservation())
        assertFalse(pre.hasObservation())
    }

    private fun context(
        sourceHierarchy: UiNodeSnapshot,
        source: VisualSessionObservationLease,
        refresh: suspend (UiNodeSnapshot) -> VisualSessionObservationLease,
        execute: (
            ExplicitVisualAction,
            String,
        ) -> AccessibilityResult<ActionExecution>,
        action: ProviderAction = visualTap(),
    ) = AuthorizedVisualSessionActionContext(
        providerAction = action,
        target = requireNotNull(providerVisualTarget(action)),
        sourceHierarchy = sourceHierarchy,
        sourceLease = source,
        sourceScreen = VisualReplayScreen(
            naturalWidth = 100,
            naturalHeight = 200,
            densityDpi = 320,
            rotation = 0,
            windowBounds = PixelBounds(0, 0, 100, 200),
            systemBars = ReplayInsets(),
            foregroundPackage = TARGET_PACKAGE,
            secureWindow = false,
            observedAtMs = Instant.parse("2026-07-26T02:00:00Z").toEpochMilli(),
        ),
        refreshObservation = refresh,
        screenProvider = { observation ->
            VisualReplayScreen(
                naturalWidth = 100,
                naturalHeight = 200,
                densityDpi = 320,
                rotation = 0,
                windowBounds = PixelBounds(0, 0, 100, 200),
                systemBars = ReplayInsets(),
                foregroundPackage = TARGET_PACKAGE,
                secureWindow = false,
                observedAtMs = Instant.parse(observation.capturedAt).toEpochMilli(),
            )
        },
        actionExecutor = execute,
        planner = dev.aiauto.android.automation.recording.replay.visual
            .ExplicitVisualReplayPlanner {
                Instant.parse("2026-07-26T02:00:03Z").toEpochMilli()
            },
    )

    private fun lease(
        id: String,
        capturedAt: String,
        image: ByteArray,
    ): VisualSessionObservationLease {
        val store = VisualObservationStore(
            trustedVerifier = TrustedVisualImageVerifier { bytes, _ -> bytes.isNotEmpty() },
        )
        val result = store.register(
            VisualObservationCapture(
                id = id,
                foregroundPackage = TARGET_PACKAGE,
                screen = VisualScreen(100, 200, 0),
                crop = PixelBounds(0, 0, 100, 200),
                capturedAt = capturedAt,
                expiresAt = "2026-07-26T02:00:10Z",
                pngBytes = image,
                hierarchy = emptyList(),
            ),
        )
        val observation = (result as VisualResult.Success).value
        return VisualSessionObservationLease(store, observation)
    }

    private fun visualTap() = ProviderAction(
        type = "ui.tap",
        params = buildJsonObject {
            put(
                "visualTarget",
                buildJsonObject {
                    put("packageName", TARGET_PACKAGE)
                    put("observationId", SOURCE_ID)
                    put("imageSha256", sha256(pngBytes(1)))
                    put("candidateId", CANDIDATE_ID)
                    put("source", "model")
                    put("confidence", 0.93)
                    put("point", buildJsonObject {
                        put("x", 0.5)
                        put("y", 0.75)
                    })
                    put("bounds", buildJsonObject {
                        put("left", 0.4)
                        put("top", 0.7)
                        put("right", 0.6)
                        put("bottom", 0.8)
                    })
                },
            )
        },
    )

    private fun visualSwipe() = visualTap().copy(
        type = "ui.swipe",
        params = buildJsonObject {
            visualTap().params.forEach(::put)
            put("start", buildJsonObject {
                put("x", 0.0)
                put("y", 0.0)
            })
            put("end", buildJsonObject {
                put("x", 1.0)
                put("y", 1.0)
            })
            put("durationMs", 450)
        },
    )

    private fun hierarchy(state: String) = UiNodeSnapshot(
        packageName = TARGET_PACKAGE,
        className = "android.view.View",
        resourceId = null,
        text = state,
        contentDescription = null,
        bounds = UiBounds(0, 0, 100, 200),
        actions = emptySet(),
        state = UiNodeState(enabled = true, visibleToUser = true),
        children = emptyList(),
    )

    private fun pngBytes(marker: Int): ByteArray =
        byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
            marker.toByte(),
        )

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"
        const val SOURCE_ID = "123e4567-e89b-42d3-a456-426614174044"
        const val PRE_ID = "123e4567-e89b-42d3-a456-426614174045"
        const val POST_ID = "123e4567-e89b-42d3-a456-426614174046"
        const val CANDIDATE_ID = "123e4567-e89b-42d3-a456-426614174047"
    }
}
