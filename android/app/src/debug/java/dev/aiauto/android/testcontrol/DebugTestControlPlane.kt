package dev.aiauto.android.testcontrol

/**
 * 功能用途：在 debug 进程内执行经一次性令牌授权的固定测试操作，且不注册可导出组件。
 */

import android.content.ComponentName
import android.content.Context

import dev.aiauto.android.BuildConfig
import dev.aiauto.android.accessibility.ScreenshotTestAccessibilityService
import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.automation.recording.RecordingProvenance
import dev.aiauto.android.automation.recording.RecordingSaveResult
import dev.aiauto.android.automation.recording.RecordingScriptStore
import dev.aiauto.android.automation.recording.ScriptRequirements
import dev.aiauto.android.bridge.AccessibilityBridgeGateway
import dev.aiauto.android.bridge.AndroidBridgeMethods
import dev.aiauto.android.bridge.BridgeCapability
import dev.aiauto.android.bridge.BridgeDispatcher
import dev.aiauto.android.bridge.BridgeErrorCode
import dev.aiauto.android.bridge.BridgeException
import dev.aiauto.android.bridge.BridgeMethodHandler
import dev.aiauto.android.bridge.BridgeSessionManager
import dev.aiauto.android.bridge.DesktopBridgeStatus
import dev.aiauto.android.bridge.NdjsonBridgeServer
import dev.aiauto.android.bridge.RecordingBridgeGateway
import dev.aiauto.android.bridge.RuntimeRecordingBridgeGateway
import dev.aiauto.testcontrol.core.N31EmulatorGate
import dev.aiauto.testcontrol.core.OneTimeTestSecret
import dev.aiauto.testcontrol.core.SafeScriptPreset
import dev.aiauto.testcontrol.core.TestControlCommand
import dev.aiauto.testcontrol.core.TestControlPolicy
import dev.aiauto.testcontrol.core.TestControlStatus
import dev.aiauto.testcontrol.core.TestIdentity
import dev.aiauto.testcontrol.core.TestScope
import dev.aiauto.testcontrol.core.TestTokenAuthority
import java.time.Duration
import java.time.Instant
import java.util.EnumSet
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DebugAccessibilityIntent(
    val enabled: Boolean,
    val componentName: ComponentName,
    val verifiedIdentity: TestIdentity,
)

class DebugBridgePairingState(
    val status: DesktopBridgeStatus,
    val expiresAt: Instant,
    val localPort: Int,
    pairingCode: CharArray,
) : AutoCloseable {
    private val pendingPairingCode = OneTimeTestSecret(pairingCode)

    @Synchronized
    fun consumePairingCode(): CharArray = pendingPairingCode.consume()

    @Synchronized
    override fun close() = pendingPairingCode.close()

    override fun toString(): String =
        "DebugBridgePairingState(status=$status, expiresAt=$expiresAt, localPort=$localPort)"
}

data class DebugSelfCheck(
    val ready: Boolean,
    val emulatorSerial: String,
    val accessibilityConnected: Boolean,
    val bridgeRunning: Boolean,
    val presetPresent: Boolean,
)

class DebugTestControlPlane(
    context: Context,
    private val identityProvider: () -> TestIdentity,
    private val tokenAuthority: TestTokenAuthority = TestTokenAuthority(),
    private val policy: TestControlPolicy = TestControlPolicy(),
    private val recordingStore: RecordingScriptStore = RecordingScriptStore.from(context),
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private var bridgeServer: NdjsonBridgeServer? = null
    private var bridgeSessions: BridgeSessionManager? = null
    private var bridgePairingState: DebugBridgePairingState? = null
    private var presetScriptId: String? = null

    init {
        check(BuildConfig.DEBUG) { "Debug test control plane requires a debug build" }
    }

    @Synchronized
    fun issueToken(scope: TestScope): String {
        val identity = currentIdentity()
        return tokenAuthority.issue(
            EnumSet.of(scope),
            identity,
            TOKEN_TTL,
        ).value()
    }

    @Synchronized
    fun status(token: String): TestControlStatus {
        val identity = authorize(token, TestControlCommand.STATUS)
        return TestControlStatus(
            identity.emulatorSerial(),
            identity.buildVariant(),
            identity.isEmulator,
            ScreenshotTestAccessibilityService.connectedService != null,
            bridgeServer != null,
            TestScope.values().map { scope -> scope.name },
        )
    }

    @Synchronized
    fun accessibilityIntent(token: String, enabled: Boolean): DebugAccessibilityIntent {
        val command = if (enabled) {
            TestControlCommand.ACCESSIBILITY_ENABLE
        } else {
            TestControlCommand.ACCESSIBILITY_DISABLE
        }
        val identity = authorize(token, command)
        return DebugAccessibilityIntent(
            enabled = enabled,
            componentName = ComponentName(
                applicationContext,
                ScreenshotTestAccessibilityService::class.java,
            ),
            verifiedIdentity = identity,
        )
    }

    @Synchronized
    fun startBridgePairing(token: String): DebugBridgePairingState {
        authorize(token, TestControlCommand.BRIDGE_PAIRING_STATE)
        stopBridge()
        val sessions = BridgeSessionManager()
        val recording: RecordingBridgeGateway = RuntimeRecordingBridgeGateway(recordingStore)
        val accessibility = object : AccessibilityBridgeGateway {
            override fun isAvailable(): Boolean =
                ScreenshotTestAccessibilityService.connectedService != null

            override fun snapshot(
                expectedPackage: String?,
            ): AccessibilityResult<UiNodeSnapshot> {
                val service = ScreenshotTestAccessibilityService.connectedService
                    ?: return AccessibilityResult.Failure(
                        AccessibilityErrorCode.SERVICE_DISABLED,
                        "The debug test accessibility service is not connected",
                    )
                val targetPackage = expectedPackage ?: applicationContext.packageName
                return service.snapshot(targetPackage)
            }

            override fun execute(
                command: AccessibilityCommand,
            ): AccessibilityResult<ActionExecution> =
                AccessibilityResult.Failure(
                    AccessibilityErrorCode.ACTION_NOT_SUPPORTED,
                    "The debug test bridge is read-only",
                )
        }
        val delegate = AndroidBridgeMethods(
            context = applicationContext,
            recording = recording,
            accessibility = accessibility,
        )
        val readOnlyHandler = object : BridgeMethodHandler {
            override fun capabilities(): List<BridgeCapability> =
                delegate.capabilities().filter { capability ->
                    capability.name in READ_ONLY_BRIDGE_METHODS
                }

            override fun handle(method: String, params: JsonObject): JsonObject {
                if (method !in READ_ONLY_BRIDGE_METHODS) {
                    throw BridgeException(
                        BridgeErrorCode.ACTION_NOT_ALLOWED,
                        "The debug test Bridge only permits read-only methods",
                    )
                }
                return delegate.handle(method, params)
            }
        }
        val dispatcher = BridgeDispatcher(
            methodHandler = readOnlyHandler,
            sessionManager = sessions,
        )
        val server = NdjsonBridgeServer(dispatcher)
        try {
            server.start()
            val pairing = sessions.issuePairingCode()
            bridgeSessions = sessions
            bridgeServer = server
            val pairingCharacters = pairing.value.toCharArray()
            return try {
                DebugBridgePairingState(
                    status = DesktopBridgeStatus.WAITING_FOR_CODE,
                    expiresAt = pairing.expiresAt,
                    localPort = server.localPort,
                    pairingCode = pairingCharacters,
                ).also { state -> bridgePairingState = state }
            } finally {
                pairingCharacters.fill('\u0000')
            }
        } catch (error: Exception) {
            bridgePairingState?.close()
            bridgePairingState = null
            server.close()
            sessions.stop()
            throw error
        }
    }

    @Synchronized
    fun presetReadOnlyScript(token: String, targetPackage: String): String {
        authorize(token, TestControlCommand.SCRIPT_PRESET)
        val preset = policy.validatePreset(
            SafeScriptPreset(
                PRESET_NAME,
                listOf(targetPackage),
                listOf(SNAPSHOT_CAPABILITY),
                false,
                false,
                false,
                false,
            ),
        )
        val scriptId = UUID.nameUUIDFromBytes(
            "${preset.id()}:${preset.targetPackages().single()}".toByteArray(),
        ).toString()
        val now = Instant.now().toString()
        val script = AutomationScript(
            id = scriptId,
            name = "N32 read-only snapshot",
            targetPackages = preset.targetPackages(),
            createdAt = now,
            updatedAt = now,
            provenance = RecordingProvenance.MANUAL,
            requirements = ScriptRequirements(
                minApiLevel = 30,
                capabilities = preset.capabilities(),
            ),
            steps = listOf(
                RecordedStep(
                    id = UUID.nameUUIDFromBytes("$scriptId:step".toByteArray()).toString(),
                    provenance = RecordingProvenance.MANUAL,
                    action = RecordedAction(
                        type = "ui.wait",
                        params = buildJsonObject {
                            put("kind", "package")
                            put("operator", "equals")
                            put("expected", targetPackage)
                            put("timeoutMs", 5_000)
                        },
                    ),
                    notes = "N32 debug-only non-sensitive snapshot fixture",
                ),
            ),
        )
        when (val result = recordingStore.save(script)) {
            is RecordingSaveResult.Saved -> {
                presetScriptId = result.script.id
                return result.script.id
            }

            is RecordingSaveResult.Conflict -> throw BridgeException(
                BridgeErrorCode.INTERNAL_ERROR,
                "The debug script preset revision conflicted",
            )
        }
    }

    @Synchronized
    fun selfCheck(token: String): DebugSelfCheck {
        val identity = authorize(token, TestControlCommand.SELF_CHECK)
        val presetPresent = presetScriptId?.let(recordingStore::get) != null
        val accessibilityConnected =
            ScreenshotTestAccessibilityService.connectedService != null
        return DebugSelfCheck(
            ready = identity.isEmulator &&
                accessibilityConnected &&
                bridgeServer != null &&
                presetPresent,
            emulatorSerial = identity.emulatorSerial(),
            accessibilityConnected = accessibilityConnected,
            bridgeRunning = bridgeServer != null,
            presetPresent = presetPresent,
        )
    }

    @Synchronized
    override fun close() {
        var cleanupFailure: Throwable? = null
        runCatching(::stopBridge).onFailure { error ->
            cleanupFailure = error
        }
        runCatching {
            presetScriptId?.let { scriptId ->
                check(recordingStore.delete(scriptId)) {
                    "Unable to delete the debug script preset"
                }
            }
            presetScriptId = null
        }.onFailure { error ->
            cleanupFailure?.addSuppressed(error) ?: run {
                cleanupFailure = error
            }
        }
        tokenAuthority.revokeAll()
        cleanupFailure?.let { throw it }
    }

    private fun authorize(token: String, command: TestControlCommand): TestIdentity {
        val identity = currentIdentity()
        return tokenAuthority.authorize(
            token,
            policy.requiredScope(command),
            identity,
        ).identity()
    }

    private fun currentIdentity(): TestIdentity =
        identityProvider().also(N31EmulatorGate::validateTrustedIdentity)

    private fun stopBridge() {
        bridgePairingState?.close()
        bridgePairingState = null
        bridgeServer?.close()
        bridgeSessions?.stop()
        bridgeServer = null
        bridgeSessions = null
    }

    private companion object {
        val TOKEN_TTL: Duration = Duration.ofMinutes(2)
        const val PRESET_NAME = "n32-read-only-snapshot"
        const val SNAPSHOT_CAPABILITY = "accessibility.snapshot"
        val READ_ONLY_BRIDGE_METHODS = setOf("bridge.rpc", "ui.snapshot")
    }
}
