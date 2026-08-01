package dev.aiauto.android.bridge

/**
 * 测试用途：验证桌面视觉 evidence 只能原子进入 N45 planner、目标包绑定动作与后置观察。
 */

import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.ActionRoute
import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import dev.aiauto.android.automation.recording.replay.visual.ReplayInsets
import dev.aiauto.android.automation.recording.replay.visual.VisualReplayScreen
import dev.aiauto.android.observe.visual.PixelBounds
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttestedVisualActionGatewayTest {
    @Test
    fun `fresh evidence executes one package bound n45 action and verifies changed hierarchy`() {
        val accessibility = FakeAccessibilityGateway(
            snapshots = ArrayDeque(
                listOf(
                    snapshot(label = "Save"),
                    snapshot(label = "Saved"),
                ),
            ),
        )
        var now = 1_785_574_801_000L
        val gateway = AndroidAttestedVisualActionGateway(
            accessibility = accessibility,
            executionAllowed = { true },
            nowMs = { now++ },
            screens = AttestedVisualScreenReader { packageName, observedAtMs ->
                screen(packageName, observedAtMs)
            },
        )

        val result = gateway.execute(validRequest())

        assertEquals(result.toString(), "committed", result.string("commitStatus"))
        assertEquals(1, result.int("actionCommits"))
        assertEquals(true, result.boolean("verified"))
        assertEquals("coordinateGesture", result.string("route"))
        assertEquals(1, accessibility.commands.size)
        val command = accessibility.commands.single() as AccessibilityCommand.Tap
        assertEquals(TARGET_PACKAGE, command.expectedPackage)
        assertEquals(50, command.point.x)
        assertEquals(80, command.point.y)
    }

    @Test
    fun `release real device and unavailable accessibility fail closed before snapshots`() {
        val accessibility = FakeAccessibilityGateway()
        val releaseGateway = AndroidAttestedVisualActionGateway(
            accessibility = accessibility,
            executionAllowed = { false },
            screens = AttestedVisualScreenReader { packageName, observedAtMs ->
                screen(packageName, observedAtMs)
            },
        )

        val releaseResult = releaseGateway.execute(validRequest())

        assertEquals("not_committed", releaseResult.string("commitStatus"))
        assertEquals(0, releaseResult.int("actionCommits"))
        assertEquals("CAPABILITY_UNAVAILABLE", releaseResult.string("errorCode"))
        assertEquals(0, accessibility.snapshotCalls)
        assertEquals(0, accessibility.commands.size)

        accessibility.available = false
        val unavailableGateway = AndroidAttestedVisualActionGateway(
            accessibility = accessibility,
            executionAllowed = { true },
            screens = AttestedVisualScreenReader { packageName, observedAtMs ->
                screen(packageName, observedAtMs)
            },
        )
        val unavailableResult = unavailableGateway.execute(validRequest())
        assertEquals("not_committed", unavailableResult.string("commitStatus"))
        assertEquals("CAPABILITY_UNAVAILABLE", unavailableResult.string("errorCode"))
        assertEquals(0, accessibility.snapshotCalls)
    }

    @Test
    fun `expired hash drift secure and package drift produce zero actions`() {
        val cases = listOf<Pair<String, (JsonObject) -> JsonObject>>(
            "expired" to { request ->
                request.withObservation {
                    put(
                        "expiresAt",
                        kotlinx.serialization.json.JsonPrimitive("2026-08-01T08:59:59Z"),
                    )
                }.withField("expiresAt", "2026-08-01T08:59:59Z")
            },
            "hash drift" to { request ->
                request.withObservation {
                    val png = getValue("png").jsonObject.toMutableMap()
                    png["sha256"] = kotlinx.serialization.json.JsonPrimitive("b".repeat(64))
                    put("png", JsonObject(png))
                }
            },
            "secure" to { request -> request },
            "package drift" to { request -> request },
        )
        for ((name, mutate) in cases) {
            val accessibility = FakeAccessibilityGateway(
                snapshots = ArrayDeque(
                    listOf(
                        if (name == "package drift") {
                            snapshot(label = "Save", packageName = "com.example.changed")
                        } else {
                            snapshot(label = "Save")
                        },
                    ),
                ),
            )
            val gateway = AndroidAttestedVisualActionGateway(
                accessibility = accessibility,
                executionAllowed = { true },
                nowMs = { 1_785_574_801_000L },
                screens = AttestedVisualScreenReader { packageName, observedAtMs ->
                    screen(
                        packageName,
                        observedAtMs,
                        secure = name == "secure",
                    )
                },
            )

            val result = gateway.execute(mutate(validRequest()))

            assertEquals(name, "not_committed", result.string("commitStatus"))
            assertEquals(name, 0, result.int("actionCommits"))
            assertFalse(name, result.boolean("verified"))
            assertEquals(name, 0, accessibility.commands.size)
        }
    }

    @Test
    fun `typed rejection is zero commit while post commit failure is unknown and never replayed`() {
        val rejectedAccessibility = FakeAccessibilityGateway(
            snapshots = ArrayDeque(listOf(snapshot(label = "Save"))),
            executeResult = AccessibilityResult.Failure(
                AccessibilityErrorCode.GESTURE_FAILED,
                "gesture rejected",
            ),
        )
        val rejectedGateway = gateway(rejectedAccessibility)

        val rejected = rejectedGateway.execute(validRequest())

        assertEquals("not_committed", rejected.string("commitStatus"))
        assertEquals(0, rejected.int("actionCommits"))
        assertEquals(rejected.toString(), 1, rejectedAccessibility.commands.size)

        val unknownAccessibility = FakeAccessibilityGateway(
            snapshots = ArrayDeque(
                listOf(
                    snapshot(label = "Save"),
                    snapshot(label = "Save"),
                ),
            ),
        )
        val unknownGateway = gateway(unknownAccessibility)

        val unknown = unknownGateway.execute(validRequest())

        assertEquals("unknown", unknown.string("commitStatus"))
        assertTrue(unknown["actionCommits"] == kotlinx.serialization.json.JsonNull)
        assertEquals("POST_ACTION_VERIFICATION_FAILED", unknown.string("errorCode"))
        assertEquals(1, unknownAccessibility.commands.size)
    }

    @Test
    fun `strict payload rejects raw coordinates and clears every decoded png copy`() {
        val accessibility = FakeAccessibilityGateway()
        val cleared = mutableListOf<ByteArray>()
        val gateway = AndroidAttestedVisualActionGateway(
            accessibility = accessibility,
            executionAllowed = { true },
            nowMs = { 1_785_574_801_000L },
            imageDecoder = AttestedVisualImageDecoder { encoded ->
                Base64.getDecoder().decode(encoded).also(cleared::add)
            },
            screens = AttestedVisualScreenReader { packageName, observedAtMs ->
                screen(packageName, observedAtMs)
            },
        )
        val invalidAction = validRequest().withAction {
            put("x", kotlinx.serialization.json.JsonPrimitive(10))
            put("y", kotlinx.serialization.json.JsonPrimitive(20))
        }

        val result = gateway.execute(invalidAction)

        assertEquals("not_committed", result.string("commitStatus"))
        assertEquals("INVALID_ARGUMENT", result.string("errorCode"))
        assertEquals(0, accessibility.snapshotCalls)
        assertEquals(0, accessibility.commands.size)
        assertTrue(cleared.isEmpty())

        val decodedResult = gateway.execute(validRequest())

        assertEquals("not_committed", decodedResult.string("commitStatus"))
        assertEquals(1, accessibility.snapshotCalls)
        assertEquals(1, cleared.size)
        assertTrue(cleared.single().all { it == 0.toByte() })
    }

    private fun gateway(
        accessibility: FakeAccessibilityGateway,
    ) = AndroidAttestedVisualActionGateway(
        accessibility = accessibility,
        executionAllowed = { true },
        nowMs = { 1_785_574_801_000L },
        screens = AttestedVisualScreenReader { packageName, observedAtMs ->
            screen(packageName, observedAtMs)
        },
    )

    private fun validRequest(): JsonObject = Json.parseToJsonElement(
        """
        {
          "deviceSerial":"emulator-5554",
          "deviceFingerprint":"${"a".repeat(64)}",
          "targetPackage":"$TARGET_PACKAGE",
          "hierarchySha256":"${"c".repeat(64)}",
          "observation":{
            "schemaVersion":"visual-observation/1.0",
            "id":"019fbcaa-0000-7000-8000-000000000047",
            "foregroundPackage":"$TARGET_PACKAGE",
            "screen":{"width":100,"height":200,"rotation":0},
            "crop":{"left":0,"top":0,"right":100,"bottom":200},
            "capturedAt":"2026-08-01T09:00:00Z",
            "expiresAt":"2026-08-01T09:00:10Z",
            "png":{
              "format":"png",
              "sizeBytes":8,
              "sha256":"${PNG_HASH}",
              "verification":"trusted-context"
            },
            "hierarchy":[]
          },
          "candidate":{
            "id":"019fbcaa-0000-7000-8000-000000000048",
            "observationId":"019fbcaa-0000-7000-8000-000000000047",
            "source":"template",
            "point":{"x":0.5,"y":0.4},
            "bounds":{"left":0.2,"top":0.2,"right":0.8,"bottom":0.6},
            "confidence":0.95
          },
          "action":{"type":"tap"},
          "capturedAt":"2026-08-01T09:00:00Z",
          "expiresAt":"2026-08-01T09:00:10Z",
          "pngBase64":"iVBORw0KGgo="
        }
        """.trimIndent(),
    ).jsonObject

    private fun snapshot(
        label: String,
        packageName: String = TARGET_PACKAGE,
    ) = UiNodeSnapshot(
        packageName = packageName,
        className = "android.widget.Button",
        resourceId = null,
        text = label,
        contentDescription = null,
        bounds = UiBounds(20, 40, 80, 120),
        actions = setOf(NodeAction.CLICK),
        state = UiNodeState(
            clickable = true,
            enabled = true,
            visibleToUser = true,
        ),
        children = emptyList(),
    )

    private fun screen(
        packageName: String,
        observedAtMs: Long,
        secure: Boolean = false,
    ) = VisualReplayScreen(
        naturalWidth = 100,
        naturalHeight = 200,
        densityDpi = 420,
        rotation = 0,
        windowBounds = PixelBounds(0, 0, 100, 200),
        systemBars = ReplayInsets(),
        foregroundPackage = packageName,
        secureWindow = secure,
        observedAtMs = observedAtMs,
    )

    private class FakeAccessibilityGateway(
        private val snapshots: ArrayDeque<UiNodeSnapshot> = ArrayDeque(),
        private val executeResult: AccessibilityResult<ActionExecution> =
            AccessibilityResult.Success(
                ActionExecution(route = ActionRoute.COORDINATE_GESTURE),
            ),
    ) : AccessibilityBridgeGateway {
        var available = true
        var snapshotCalls = 0
        val commands = mutableListOf<AccessibilityCommand>()

        override fun isAvailable(): Boolean = available

        override fun snapshot(
            expectedPackage: String?,
        ): AccessibilityResult<UiNodeSnapshot> {
            snapshotCalls += 1
            return snapshots.removeFirstOrNull()
                ?.let(AccessibilityResult<UiNodeSnapshot>::Success)
                ?: AccessibilityResult.Failure(
                    AccessibilityErrorCode.SNAPSHOT_FAILED,
                    "snapshot unavailable",
                )
        }

        override fun execute(
            command: AccessibilityCommand,
        ): AccessibilityResult<ActionExecution> {
            commands += command
            return executeResult
        }
    }

    private fun JsonObject.withObservation(
        block: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit,
    ): JsonObject {
        val changed = toMutableMap()
        val observation = getValue("observation").jsonObject.toMutableMap().apply(block)
        changed["observation"] = JsonObject(observation)
        return JsonObject(changed)
    }

    private fun JsonObject.withAction(
        block: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit,
    ): JsonObject {
        val changed = toMutableMap()
        val action = getValue("action").jsonObject.toMutableMap().apply(block)
        changed["action"] = JsonObject(action)
        return JsonObject(changed)
    }

    private fun JsonObject.withField(name: String, value: String): JsonObject =
        JsonObject(toMutableMap().apply {
            put(name, kotlinx.serialization.json.JsonPrimitive(value))
        })

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.content

    private fun JsonObject.int(name: String): Int =
        getValue(name).jsonPrimitive.content.toInt()

    private fun JsonObject.boolean(name: String): Boolean =
        getValue(name).jsonPrimitive.content.toBoolean()

    private companion object {
        const val TARGET_PACKAGE = "com.example.notes"
        // 该 hash 对应测试请求中的最小 PNG signature 字节。
        const val PNG_HASH = "4c4b6a3be1314ab86138bef4314dde022e600960d8689a2c8f8631802d20dab6"
    }
}
