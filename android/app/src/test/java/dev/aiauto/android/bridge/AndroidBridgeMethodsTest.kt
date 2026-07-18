package dev.aiauto.android.bridge

import android.content.Context

import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.buildJsonObject
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
}
