package dev.aiauto.android.testcontrol

/**
 * 测试用途：在 N31 模拟器上验证 debug 控制面、最小设置授权、Bridge 配对和只读快照闭环。
 */

import android.app.Activity
import android.content.Intent
import android.os.SystemClock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import dev.aiauto.android.accessibility.ScreenshotTestAccessibilityService
import dev.aiauto.android.accessibility.ScreenshotTestActivity
import dev.aiauto.android.bridge.BridgeProtocol
import dev.aiauto.android.bridge.DesktopBridgeStatus
import dev.aiauto.testcontrol.core.TestScope
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugTestControlPlaneDeviceTest {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    @Test
    fun emulatorControlPlaneCreatesReadOnlyBridgeAndRestoresSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val identityProvider = InstrumentationTestIdentityProvider(
            targetContext = targetContext,
            instrumentationContext = instrumentation.context,
            arguments = InstrumentationRegistry.getArguments(),
        )
        val control = DebugTestControlPlane(
            context = targetContext,
            identityProvider = identityProvider::current,
        )
        var activity: Activity? = null
        try {
            val initialStatus = control.status(control.issueToken(TestScope.STATUS_READ))
            assertTrue(initialStatus.emulator())
            assertEquals(identityProvider.current().emulatorSerial(), initialStatus.emulatorSerial())
            assertFalse(initialStatus.bridgeRunning())

            val enable = control.accessibilityIntent(
                control.issueToken(TestScope.ACCESSIBILITY_CONTROL),
                enabled = true,
            )
            val disable = control.accessibilityIntent(
                control.issueToken(TestScope.ACCESSIBILITY_CONTROL),
                enabled = false,
            )
            SecureAccessibilitySettings(
                instrumentation = instrumentation,
                context = targetContext,
                identityProvider = identityProvider::current,
            ).withServiceEnabled(enable, disable) {
                try {
                    activity = instrumentation.startActivitySync(
                        Intent(targetContext, ScreenshotTestActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    instrumentation.waitForIdleSync()
                    assertNotNull(
                        ScreenshotTestAccessibilityService.awaitConnected(TIMEOUT_SECONDS),
                    )

                    val scriptId = control.presetReadOnlyScript(
                        control.issueToken(TestScope.SCRIPT_PRESET),
                        targetContext.packageName,
                    )
                    assertTrue(UUID.fromString(scriptId).toString() == scriptId)

                    val pairing = control.startBridgePairing(
                        control.issueToken(TestScope.BRIDGE_PAIRING),
                    )
                    assertEquals(DesktopBridgeStatus.WAITING_FOR_CODE, pairing.status)
                    assertTrue(pairing.localPort > 0)
                    val pairingCode = pairing.consumePairingCode()
                    assertThrows(IllegalStateException::class.java) {
                        pairing.consumePairingCode()
                    }
                    assertFalse(pairing.toString().contains("value="))
                    val snapshot = try {
                        runReadOnlySnapshot(
                            port = pairing.localPort,
                            pairingCode = pairingCode,
                            targetPackage = targetContext.packageName,
                        )
                    } finally {
                        pairingCode.fill('\u0000')
                    }
                    assertEquals(
                        targetContext.packageName,
                        snapshot.getValue("packageName").jsonPrimitive.content,
                    )

                    val selfCheck = control.selfCheck(
                        control.issueToken(TestScope.SELF_CHECK_READ),
                    )
                    assertTrue(selfCheck.ready)
                    assertTrue(selfCheck.accessibilityConnected)
                    assertTrue(selfCheck.bridgeRunning)
                    assertTrue(selfCheck.presetPresent)
                } finally {
                    activity?.finish()
                    instrumentation.waitForIdleSync()
                    control.close()
                }
            }
        } finally {
            activity?.finish()
            control.close()
        }
    }

    private fun runReadOnlySnapshot(
        port: Int,
        pairingCode: CharArray,
        targetPackage: String,
    ): JsonObject {
        Socket(BridgeProtocol.LOOPBACK_ADDRESS, port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            exchange(
                writer,
                reader,
                request(
                    method = "rpc.hello",
                    params = buildJsonObject {
                        put("clientVersion", "0.1.0")
                        put(
                            "supportedProtocolVersions",
                            buildJsonArray { add(JsonPrimitive(BridgeProtocol.VERSION)) },
                        )
                        put("capabilities", buildJsonArray { })
                    },
                ),
            ).also { hello ->
                val capabilities = hello.getValue("result")
                    .jsonObject
                    .getValue("capabilities")
                    .toString()
                assertFalse(capabilities.contains("action.execute"))
                assertFalse(capabilities.contains("device.info"))
                assertFalse(capabilities.contains("recording.replay"))
            }
            val open = exchange(
                writer,
                reader,
                request(
                    method = "session.open",
                    params = buildJsonObject {
                        put("pairingCode", String(pairingCode))
                        put("hostName", "n32-instrumentation")
                    },
                ),
            )
            val bridgeToken = open.getValue("result")
                .jsonObject
                .getValue("token")
                .jsonPrimitive
                .content
            val snapshot = exchange(
                writer,
                reader,
                request(
                    method = "ui.snapshot",
                    params = buildJsonObject {
                        put("targetPackage", targetPackage)
                        put("maxDepth", 16)
                    },
                    token = bridgeToken,
                ),
            ).getValue("result").jsonObject.getValue("root").jsonObject
            exchange(
                writer,
                reader,
                request(
                    method = "session.close",
                    params = buildJsonObject { },
                    token = bridgeToken,
                ),
            )
            return snapshot
        }
    }

    private fun request(
        method: String,
        params: JsonObject,
        token: String? = null,
    ): JsonObject = buildJsonObject {
        put("jsonrpc", BridgeProtocol.JSON_RPC_VERSION)
        put("id", UUID.randomUUID().toString())
        put("requestId", UUID.randomUUID().toString())
        put("protocolVersion", BridgeProtocol.VERSION)
        put("method", method)
        put("params", params)
        put("deadlineMs", SOCKET_TIMEOUT_MS)
        token?.let { put("token", it) }
    }

    private fun exchange(
        writer: BufferedWriter,
        reader: BufferedReader,
        request: JsonObject,
    ): JsonObject {
        writer.write(request.toString())
        writer.newLine()
        writer.flush()
        val line = checkNotNull(reader.readLine()) {
            "Bridge closed before returning a response"
        }
        val response = json.parseToJsonElement(line).jsonObject
        check("error" !in response) {
            "Bridge returned a structured failure"
        }
        return response
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val SOCKET_TIMEOUT_MS = 15_000
    }
}
