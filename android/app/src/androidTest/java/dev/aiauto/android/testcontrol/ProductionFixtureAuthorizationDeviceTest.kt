package dev.aiauto.android.testcontrol

/**
 * 测试用途：验证固定模拟器授权端点仅临时启用生产无障碍和完整桥接，并在停止后恢复全部设置。
 */

import android.content.ComponentName
import android.os.SystemClock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import dev.aiauto.android.accessibility.AccessibilityRuntime
import dev.aiauto.android.accessibility.AiAutomationAccessibilityService
import dev.aiauto.android.accessibility.settings.AccessibilitySettings
import dev.aiauto.android.accessibility.settings.AccessibilitySettingsRepository
import dev.aiauto.android.bridge.BridgeLimits
import dev.aiauto.android.bridge.BridgeProtocol
import dev.aiauto.android.bridge.DesktopBridgeController
import dev.aiauto.android.bridge.DesktopBridgeStatus
import dev.aiauto.testcontrol.core.FixedProductionFixtureAuthorization
import dev.aiauto.testcontrol.core.ProductionFixtureControlRequest
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionFixtureAuthorizationDeviceTest {
    @Test
    fun serveFixedProductionFixtureAuthorization() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val targetContext = instrumentation.targetContext
        val identityProvider = InstrumentationTestIdentityProvider(
            targetContext = targetContext,
            instrumentationContext = instrumentation.context,
            arguments = arguments,
        )
        val expectedRunId = requiredArgument(arguments.getString(ARGUMENT_RUN_ID))
        val scenarioId = requiredArgument(arguments.getString(ARGUMENT_SCENARIO_ID))
        val settings = AccessibilitySettingsRepository.from(targetContext)
        val originalSettings = settings.load()
        val controller = DesktopBridgeController.from(targetContext)
        var stopConnection: ControlConnection? = null

        ServerSocket().use { server ->
            server.reuseAddress = true
            server.soTimeout = CONTROL_LIFETIME_MS
            server.bind(
                InetSocketAddress(
                    InetAddress.getByName(BridgeProtocol.LOOPBACK_ADDRESS),
                    CONTROL_PORT,
                ),
                1,
            )
            val setup = server.acceptControl()
            try {
                val setupRequest = setup.readRequest()
                check(setupRequest.command() == "setup" && setupRequest.runId() == expectedRunId) {
                    "Production fixture setup identity did not match the instrumentation run"
                }
                val identity = identityProvider.current()
                val grant = FixedProductionFixtureAuthorization.authorize(
                    setupRequest.token(),
                    identity,
                    scenarioId,
                )
                settings.save(
                    AccessibilitySettings(
                        disclosureAccepted = true,
                        targetPackages = grant.targetPackages(),
                    ),
                )
                val component = ComponentName(
                    targetContext,
                    AiAutomationAccessibilityService::class.java,
                )
                val enable = DebugAccessibilityIntent(true, component, identity)
                val disable = DebugAccessibilityIntent(false, component, identity)
                SecureAccessibilitySettings(
                    instrumentation = instrumentation,
                    context = targetContext,
                    identityProvider = identityProvider::current,
                    isServiceConnected = AccessibilityRuntime::isAvailable,
                ).withServiceEnabled(enable, disable) {
                    FixedProductionFixtureAuthorization.validateUnchanged(
                        grant,
                        identityProvider.current(),
                    )
                    controller.start()
                    val ready = awaitBridgeReady(controller)
                    setup.write(
                        "{\"ok\":true,\"runId\":\"$expectedRunId\","
                            + "\"ready\":true,\"bridgePort\":${BridgeLimits.PORT},"
                            + "\"pairingCode\":\"${checkNotNull(ready.pairingCode)}\","
                            + "\"desktopDeviceFingerprint\":\"${identity.avdFingerprint()}\"}",
                    )
                    setup.close()

                    stopConnection = server.acceptControl()
                    val stopRequest = checkNotNull(stopConnection).readRequest()
                    check(stopRequest.command() == "stop" && stopRequest.runId() == expectedRunId) {
                        "Production fixture stop identity did not match the instrumentation run"
                    }
                    val stopGrant = FixedProductionFixtureAuthorization.authorize(
                        stopRequest.token(),
                        identityProvider.current(),
                        scenarioId,
                    )
                    FixedProductionFixtureAuthorization.validateUnchanged(
                        stopGrant,
                        identityProvider.current(),
                    )
                    controller.stop()
                    settings.save(originalSettings)
                }
                stopConnection?.write(
                    "{\"ok\":true,\"runId\":\"$expectedRunId\",\"stopped\":true}",
                )
            } finally {
                runCatching(controller::stop)
                runCatching { settings.save(originalSettings) }
                runCatching(setup::close)
                runCatching { stopConnection?.close() }
            }
        }
    }

    private fun awaitBridgeReady(controller: DesktopBridgeController) =
        generateSequence {
            val state = controller.state.value
            if (
                state.status == DesktopBridgeStatus.WAITING_FOR_CODE &&
                state.pairingCode?.matches(PAIRING_CODE) == true
            ) {
                state
            } else {
                null
            }
        }.firstOrNull() ?: run {
            val deadline = SystemClock.uptimeMillis() + BRIDGE_START_TIMEOUT_MS
            var state = controller.state.value
            while (SystemClock.uptimeMillis() < deadline) {
                state = controller.state.value
                if (
                    state.status == DesktopBridgeStatus.WAITING_FOR_CODE &&
                    state.pairingCode?.matches(PAIRING_CODE) == true
                ) {
                    return@run state
                }
                check(state.status != DesktopBridgeStatus.ERROR) {
                    "Production Bridge entered an error state"
                }
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
            error("Production Bridge did not become ready before the fixed timeout")
        }

    private fun ServerSocket.acceptControl(): ControlConnection =
        ControlConnection(accept().apply { soTimeout = CONTROL_EXCHANGE_TIMEOUT_MS })

    private fun requiredArgument(value: String?): String =
        checkNotNull(value?.takeIf(String::isNotBlank)) {
            "Missing required N47 instrumentation argument"
        }

    private class ControlConnection(
        private val socket: Socket,
    ) : AutoCloseable {
        private val reader = BufferedReader(
            InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
        )
        private val writer = BufferedWriter(
            OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
        )

        fun readRequest(): ProductionFixtureControlRequest {
            val line = checkNotNull(reader.readLine()) {
                "Production fixture control connection closed before a request"
            }
            check(reader.readLine() == null) {
                "Production fixture control connection contained trailing data"
            }
            return ProductionFixtureControlRequest.parse(line)
        }

        fun write(response: String) {
            writer.write(response)
            writer.newLine()
            writer.flush()
        }

        override fun close() {
            runCatching(reader::close)
            runCatching(writer::close)
            runCatching(socket::close)
        }
    }

    private companion object {
        const val ARGUMENT_SCENARIO_ID = "n47ScenarioId"
        const val ARGUMENT_RUN_ID = "n47RunId"
        const val CONTROL_PORT = 38_484
        const val CONTROL_LIFETIME_MS = 10 * 60 * 1_000
        const val CONTROL_EXCHANGE_TIMEOUT_MS = 30_000
        const val BRIDGE_START_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 100L
        val PAIRING_CODE = Regex("^[0-9]{6}$")
    }
}
