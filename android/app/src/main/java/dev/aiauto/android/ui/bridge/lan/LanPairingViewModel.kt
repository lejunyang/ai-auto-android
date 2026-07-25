package dev.aiauto.android.ui.bridge.lan

/**
 * 功能用途：编排 LAN invitation、人工确认和短期后台连接，并在生命周期结束时失败关闭。
 */

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

import dev.aiauto.android.bridge.lan.AndroidLanNetworkDirectory
import dev.aiauto.android.bridge.lan.AndroidLanPairingSessionLauncher
import dev.aiauto.android.bridge.lan.LanConnectRequest
import dev.aiauto.android.bridge.lan.LanLocalInterface
import dev.aiauto.android.bridge.lan.LanOutboundSession
import dev.aiauto.android.bridge.lan.LanProtocolException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface LanPairingSession : AutoCloseable {
    val expiresAt: Instant
}

fun interface LanPairingSessionLauncher : AutoCloseable {
    fun connect(request: LanConnectRequest): LanPairingSession

    override fun close() = Unit
}

data class LanPairingScreenState(
    val pairing: LanPairingUiState = LanPairingUiState(
        scannerAvailability = ScannerAvailability.PROVIDER_UNAVAILABLE,
    ),
    val localInterfaces: List<LanLocalInterface> = emptyList(),
)

class LanPairingViewModel(
    private val invitationInput: StrictLanInvitationInputPort,
    private val sessionLauncher: LanPairingSessionLauncher,
    private val localInterfaces: () -> List<LanLocalInterface>,
    private val networkSelector: (LanLocalInterface) -> Boolean,
    private val executor: Executor,
    private val closeExecutor: () -> Unit,
) : ViewModel(), AutoCloseable {
    private val machine = LanPairingStateMachine(invitationInput)
    private val stateStore = MutableStateFlow(LanPairingScreenState())
    private val closed = AtomicBoolean(false)
    private var generation = 0L
    private var session: LanPairingSession? = null
    private var pendingRequest: LanConnectRequest? = null

    val uiState: StateFlow<LanPairingScreenState> = stateStore.asStateFlow()

    init {
        machine.onScannerUnavailable()
        refreshInterfaces()
    }

    @Synchronized
    fun refreshInterfaces() {
        if (closed.get()) return
        val interfaces = runCatching(localInterfaces).getOrDefault(emptyList())
        publish(interfaces)
    }

    @Synchronized
    fun submitManualInvitation(payload: String) {
        if (closed.get() || machine.state.stopAvailable) return
        machine.onManualPayload(payload)
        refreshInterfaces()
    }

    @Synchronized
    fun submitScannedInvitation(payload: String) {
        if (closed.get() || machine.state.stopAvailable) return
        machine.onScannedPayload(payload)
        refreshInterfaces()
    }

    @Synchronized
    fun selectCandidate(host: String, interfaceId: String) {
        if (closed.get() || machine.state.phase != LanPairingPhase.REVIEW) return
        machine.selectCandidate(host, interfaceId)
        publish()
    }

    @Synchronized
    fun selectLocalInterface(localInterface: LanLocalInterface) {
        if (
            closed.get() ||
            machine.state.phase != LanPairingPhase.REVIEW ||
            localInterface !in stateStore.value.localInterfaces
        ) {
            return
        }
        machine.selectLocalInterface(localInterface)
        publish()
    }

    @Synchronized
    fun confirmFingerprint(input: String) {
        if (closed.get() || machine.state.phase != LanPairingPhase.REVIEW) return
        machine.confirmFingerprint(input)
        publish()
    }

    @Synchronized
    fun connect() {
        if (closed.get() || !machine.state.canRequestConnection) return
        val selected = machine.state.selectedLocalInterface ?: return
        if (!networkSelector(selected)) {
            machine.onFailure("LAN_INTERFACE_MISMATCH")
            publish()
            return
        }
        val request = try {
            invitationInput.takeConnectRequest(machine.state, REQUIRED_CAPABILITIES)
        } catch (error: LanProtocolException) {
            machine.onFailure(error.code)
            publish()
            return
        }
        pendingRequest = request
        machine.beginConnecting()
        val operation = ++generation
        publish()
        try {
            executor.execute {
                connectInBackground(operation, request)
            }
        } catch (_: Exception) {
            if (pendingRequest === request) pendingRequest = null
            request.invitation.close()
            machine.onFailure("LAN_CONNECTION_FAILED")
            publish()
        }
    }

    @Synchronized
    fun stop() {
        if (closed.get()) return
        generation += 1
        pendingRequest?.invitation?.close()
        pendingRequest = null
        session?.close()
        session = null
        sessionLauncher.close()
        invitationInput.clear()
        machine.stop()
        publish()
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation += 1
        pendingRequest?.invitation?.close()
        pendingRequest = null
        session?.close()
        session = null
        sessionLauncher.close()
        invitationInput.close()
        machine.stop()
        publish(emptyList())
        closeExecutor()
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    private fun connectInBackground(
        operation: Long,
        request: LanConnectRequest,
    ) {
        synchronized(this) {
            if (closed.get() || operation != generation) {
                if (pendingRequest === request) pendingRequest = null
                request.invitation.close()
                return
            }
        }
        val connected = try {
            sessionLauncher.connect(request)
        } catch (error: LanProtocolException) {
            synchronized(this) {
                if (pendingRequest === request) pendingRequest = null
                if (!closed.get() && operation == generation) {
                    machine.onFailure(error.code)
                    publish()
                }
            }
            return
        } catch (_: Exception) {
            request.invitation.close()
            synchronized(this) {
                if (pendingRequest === request) pendingRequest = null
                if (!closed.get() && operation == generation) {
                    machine.onFailure("LAN_CONNECTION_FAILED")
                    publish()
                }
            }
            return
        }
        synchronized(this) {
            if (pendingRequest === request) pendingRequest = null
            if (closed.get() || operation != generation) {
                connected.close()
                return
            }
            session = connected
            machine.onConnected(connected.expiresAt)
            publish()
        }
    }

    private fun publish(
        interfaces: List<LanLocalInterface> = stateStore.value.localInterfaces,
    ) {
        stateStore.value = LanPairingScreenState(
            pairing = machine.state,
            localInterfaces = interfaces,
        )
    }

    companion object {
        val REQUIRED_CAPABILITIES = listOf(
            "lan.bridge.mutual-confirmation.v1",
            "lan.bridge.rpc.v1",
        )

        fun factory(context: Context): ViewModelProvider.Factory {
            val applicationContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val directory = AndroidLanNetworkDirectory.from(applicationContext)
                    val executor = Executors.newSingleThreadExecutor { task ->
                        Thread(task, "lan-pairing-connect").apply { isDaemon = true }
                    }
                    val launcher = AndroidLanPairingSessionLauncher(
                        context = applicationContext,
                        directory = directory,
                    )
                    val sessionLauncher = object : LanPairingSessionLauncher {
                        override fun connect(request: LanConnectRequest): LanPairingSession =
                            launcher.connect(request).asPairingSession()

                        override fun close() {
                            launcher.close()
                        }
                    }
                    return LanPairingViewModel(
                        invitationInput = StrictLanInvitationInputPort { Instant.now() },
                        sessionLauncher = sessionLauncher,
                        localInterfaces = directory::interfaces,
                        networkSelector = { directory.current(it) != null },
                        executor = executor,
                        closeExecutor = executor::shutdownNow,
                    ) as T
                }
            }
        }

        private fun LanOutboundSession.asPairingSession(): LanPairingSession =
            object : LanPairingSession {
                override val expiresAt: Instant
                    get() = this@asPairingSession.expiresAt

                override fun close() {
                    this@asPairingSession.close()
                }
            }
    }
}
