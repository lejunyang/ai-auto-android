package dev.aiauto.android.bridge

import android.content.Context

import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.automation.recording.ReplayReport
import dev.aiauto.android.automation.recording.ReplayStepResult
import dev.aiauto.android.automation.recording.ReplayStepStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidBridgeMethodsTest {
    @Test
    fun `unavailable accessibility returns capability error without invoking it`() {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val gateway = UnavailableGateway()
        val methods = AndroidBridgeMethods(context, accessibility = gateway)

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
        private val report: ReplayReport?,
    ) : RecordingBridgeGateway {
        var lastScriptId: String? = null

        override fun isAvailable(): Boolean = true

        override fun replay(scriptId: String): ReplayReport? {
            lastScriptId = scriptId
            return report
        }
    }
}
