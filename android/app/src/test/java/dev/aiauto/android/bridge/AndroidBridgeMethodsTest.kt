package dev.aiauto.android.bridge

/**
 * 测试用途：验证 AndroidBridgeMethods 的功能契约、失败语义及自动化安全边界。
 */

import android.content.Context

import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.AutomationScriptSummary
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.automation.recording.ReplayReport
import dev.aiauto.android.automation.recording.ReplayStepResult
import dev.aiauto.android.automation.recording.ReplayStepStatus
import dev.aiauto.android.automation.recording.ScriptRequirements
import dev.aiauto.android.automation.recording.ScriptVariable
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidBridgeMethodsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `unavailable accessibility returns capability error without invoking it`() {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val gateway = UnavailableGateway()
        val methods = AndroidBridgeMethods(
            context = context,
            recording = FakeRecordingGateway(),
            accessibility = gateway,
        )

        val error = assertThrows(BridgeException::class.java) {
            methods.handle("ui.snapshot", buildJsonObject {})
        }

        assertEquals(BridgeErrorCode.CAPABILITY_UNAVAILABLE, error.code)
        assertEquals(0, gateway.snapshotCalls)
        assertFalse(
            methods.capabilities()
                .single { it.name == "ui.snapshot" }
                .available,
        )
    }

    @Test
    fun `attested visual method is exposed only through its injected narrow gateway`() {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val visual = FakeAttestedVisualGateway()
        val methods = AndroidBridgeMethods(
            context = context,
            recording = FakeRecordingGateway(),
            accessibility = UnavailableGateway(),
            attestedVisual = visual,
        )
        val params = buildJsonObject { put("opaqueEvidence", "test-only") }

        val result = methods.handle("visual.action.execute", params)

        assertEquals(params, visual.lastParams)
        assertEquals(JsonPrimitive("not_committed"), result["commitStatus"])
        assertTrue(
            methods.capabilities()
                .single { it.name == "visual.action.execute" }
                .available,
        )
    }

    @Test
    fun `recording list returns only sanitized protocol summary fields`() {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val summary = AutomationScriptSummary(
            id = "123e4567-e89b-42d3-a456-426614174000",
            name = "Save a note",
            targetPackages = listOf("com.example.notes"),
            stepCount = 3,
            createdAt = "2026-07-18T01:30:00Z",
            requirements = ScriptRequirements(
                minApiLevel = 30,
                capabilities = listOf("accessibility.snapshot", "accessibility.action"),
            ),
        )
        val methods = AndroidBridgeMethods(
            context = context,
            recording = FakeRecordingGateway(summaries = listOf(summary)),
            accessibility = UnavailableGateway(),
        )

        val result = methods.handle("recording.list", buildJsonObject {})

        val expected = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("recording-list-result.json"),
        ).bufferedReader().use { reader ->
            Json.parseToJsonElement(reader.readText()).jsonObject
        }
        val encoded = result.toString()
        val recording = result.getValue("recordings").jsonArray.single().jsonObject
        assertEquals(expected, result)
        assertEquals(
            setOf(
                "id",
                "name",
                "targetPackages",
                "stepCount",
                "createdAt",
                "requirements",
            ),
            recording.keys,
        )
        assertEquals(JsonPrimitive(3), recording["stepCount"])
        assertEquals(
            30,
            recording.getValue("requirements").jsonObject
                .getValue("minApiLevel").jsonPrimitive.content.toInt(),
        )
        assertFalse(encoded.contains("variables"))
        assertFalse(encoded.contains("secretRef"))
        assertTrue(
            methods.capabilities()
                .single { it.name == "recording.list" }
                .available,
        )
    }

    @Test
    fun `bridge factory lists scripts from the real app store without secret metadata`() {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns temporaryFolder.root
        val store = RecordingScriptStore.from(context)
        store.save(secretScript())

        val result = AndroidBridgeMethodsFactory.create(context)
            .handle("recording.list", buildJsonObject {})

        val encoded = result.toString()
        assertTrue(encoded.contains("Save a note"))
        assertTrue(encoded.contains("accessibility.action"))
        assertFalse(encoded.contains("account.password"))
        assertFalse(encoded.contains("secretRef"))
        assertFalse(encoded.contains("variables"))
    }

    @Test
    fun `recording list rejects unsupported parameters before reading storage`() {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val recording = FakeRecordingGateway()
        val methods = AndroidBridgeMethods(
            context = context,
            recording = recording,
            accessibility = UnavailableGateway(),
        )

        val error = assertThrows(BridgeException::class.java) {
            methods.handle(
                "recording.list",
                buildJsonObject { put("includeSecrets", true) },
            )
        }

        assertEquals(BridgeErrorCode.INVALID_ARGUMENT, error.code)
        assertEquals(0, recording.listCalls)
    }

    @Test
    fun `recording replay delegates an explicit script and returns a report`() {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val scriptId = "123e4567-e89b-42d3-a456-426614174000"
        val recording = FakeRecordingGateway(
            report = ReplayReport(
                scriptId = scriptId,
                startedAtMs = 10,
                finishedAtMs = 20,
                succeeded = true,
                requiresIntervention = false,
                steps = listOf(
                    ReplayStepResult(
                        stepId = "step-1",
                        status = ReplayStepStatus.SUCCEEDED,
                        attempts = 1,
                        route = "nodeAction",
                    ),
                ),
            ),
        )
        val methods = AndroidBridgeMethods(
            context = context,
            accessibility = UnavailableGateway(),
            recording = recording,
        )

        val result = methods.handle(
            "recording.replay",
            buildJsonObject {
                put("scriptId", scriptId)
                put("idempotencyKey", "123e4567-e89b-42d3-a456-426614174001")
            },
        )

        assertEquals(scriptId, recording.lastScriptId)
        assertEquals(JsonPrimitive(true), result["succeeded"])
    }

    @Test
    fun `recording replay reports a missing script without executing a guess`() {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val methods = AndroidBridgeMethods(
            context = context,
            accessibility = UnavailableGateway(),
            recording = FakeRecordingGateway(report = null),
        )

        val error = assertThrows(BridgeException::class.java) {
            methods.handle(
                "recording.replay",
                buildJsonObject {
                    put("scriptId", "123e4567-e89b-42d3-a456-426614174000")
                    put("idempotencyKey", "123e4567-e89b-42d3-a456-426614174001")
                },
            )
        }

        assertEquals(BridgeErrorCode.SCRIPT_NOT_FOUND, error.code)
    }

    private class UnavailableGateway : AccessibilityBridgeGateway {
        var snapshotCalls = 0

        override fun isAvailable(): Boolean = false

        override fun snapshot(
            expectedPackage: String?,
        ): AccessibilityResult<UiNodeSnapshot> {
            snapshotCalls += 1
            error("snapshot must not run")
        }

        override fun execute(
            command: AccessibilityCommand,
        ): AccessibilityResult<ActionExecution> = error("execute must not run")
    }

    private class FakeRecordingGateway(
        private val summaries: List<AutomationScriptSummary> = emptyList(),
        private val report: ReplayReport? = null,
    ) : RecordingBridgeGateway {
        var lastScriptId: String? = null
        var listCalls = 0

        override fun isAvailable(): Boolean = true

        override fun list(): List<AutomationScriptSummary> {
            listCalls += 1
            return summaries
        }

        override fun replay(scriptId: String): ReplayReport? {
            lastScriptId = scriptId
            return report
        }
    }

    private class FakeAttestedVisualGateway : AttestedVisualActionBridgeGateway {
        var lastParams: JsonObject? = null

        override fun isAvailable(): Boolean = true

        override fun execute(params: JsonObject): JsonObject {
            lastParams = params
            return buildJsonObject {
                put("commitStatus", "not_committed")
                put("actionCommits", 0)
                put("verified", false)
            }
        }
    }

    private fun secretScript() = AutomationScript(
        id = "123e4567-e89b-42d3-a456-426614174000",
        name = "Save a note",
        targetPackages = listOf("com.example.notes"),
        createdAt = "2026-07-18T01:30:00Z",
        requirements = ScriptRequirements(
            minApiLevel = 30,
            capabilities = listOf("accessibility.snapshot", "accessibility.action"),
        ),
        variables = listOf(
            ScriptVariable(
                name = "account.password",
                type = "secret",
                sensitive = true,
            ),
        ),
        steps = listOf(
            RecordedStep(
                id = "123e4567-e89b-42d3-a456-426614174001",
                recordedAtMs = 0,
                action = RecordedAction(
                    type = "ui.setText",
                    params = JsonObject(
                        mapOf(
                            "secretRef" to JsonPrimitive("account.password"),
                        ),
                    ),
                ),
            ),
        ),
    )
}
