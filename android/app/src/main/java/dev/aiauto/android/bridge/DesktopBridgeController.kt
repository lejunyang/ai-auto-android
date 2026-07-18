package dev.aiauto.android.bridge

import android.content.Context
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DesktopBridgeStatus {
    STOPPED,
    STARTING,
    WAITING_FOR_CODE,
    CONNECTED,
    ERROR,
}

data class DesktopBridgeState(
    val status: DesktopBridgeStatus = DesktopBridgeStatus.STOPPED,
    val pairingCode: String? = null,
    val pairingCodeExpiresAt: Instant? = null,
    val connectedHost: String? = null,
    val sessionExpiresAt: Instant? = null,
    val errorMessage: String? = null,
) {
    val enabled: Boolean
        get() = status != DesktopBridgeStatus.STOPPED &&
            status != DesktopBridgeStatus.ERROR
}

class DesktopBridgeController private constructor(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val stateStore = MutableStateFlow(DesktopBridgeState())
    private val controlExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "bridge-control").apply { isDaemon = true }
    }
    private val expiryExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "bridge-code-expiry").apply { isDaemon = true }
    }

    @Volatile
    private var server: NdjsonBridgeServer? = null

    @Volatile
    private var sessions: BridgeSessionManager? = null

    val state: StateFlow<DesktopBridgeState> = stateStore.asStateFlow()

    @Synchronized
    fun start() {
        if (server != null || stateStore.value.status == DesktopBridgeStatus.STARTING) {
            return
        }
        stateStore.value = DesktopBridgeState(status = DesktopBridgeStatus.STARTING)
        controlExecutor.execute(::startServer)
    }

    private fun startServer() {
        val observer = object : BridgeSessionObserver {
            override fun onSessionOpened(hostName: String, expiresAt: Instant) {
                stateStore.update { current ->
                    if (current.enabled) {
                        current.copy(
                            status = DesktopBridgeStatus.CONNECTED,
                            pairingCode = null,
                            pairingCodeExpiresAt = null,
                            connectedHost = hostName,
                            sessionExpiresAt = expiresAt,
                            errorMessage = null,
                        )
                    } else {
                        current
                    }
                }
                val delayMillis = (expiresAt.toEpochMilli() - Instant.now().toEpochMilli())
                    .coerceAtLeast(0)
                expiryExecutor.schedule(
                    {
                        stateStore.update { current ->
                            if (current.sessionExpiresAt == expiresAt) {
                                current.copy(
                                    status = DesktopBridgeStatus.WAITING_FOR_CODE,
                                    connectedHost = null,
                                    sessionExpiresAt = null,
                                )
                            } else {
                                current
                            }
                        }
                    },
                    delayMillis,
                    TimeUnit.MILLISECONDS,
                )
            }

            override fun onSessionClosed() {
                stateStore.update { current ->
                    if (current.enabled) {
                        current.copy(
                            status = DesktopBridgeStatus.WAITING_FOR_CODE,
                            pairingCode = null,
                            pairingCodeExpiresAt = null,
                            connectedHost = null,
                            sessionExpiresAt = null,
                        )
                    } else {
                        current
                    }
                }
            }
        }
        val newSessions = BridgeSessionManager(observer = observer)
        val dispatcher = BridgeDispatcher(
            methodHandler = AndroidBridgeMethods(applicationContext),
            sessionManager = newSessions,
        )
        val newServer = NdjsonBridgeServer(dispatcher)
        try {
            newServer.start()
            synchronized(this) {
                if (stateStore.value.status != DesktopBridgeStatus.STARTING) {
                    newServer.close()
                    newSessions.stop()
                    return
                }
                sessions = newSessions
                server = newServer
            }
            val pairingCode = newSessions.issuePairingCode()
            synchronized(this) {
                if (sessions !== newSessions || server !== newServer) {
                    return
                }
                publishPairingCode(pairingCode)
            }
        } catch (error: Exception) {
            newServer.close()
            newSessions.stop()
            synchronized(this) {
                if (stateStore.value.status == DesktopBridgeStatus.STARTING) {
                    stateStore.value = DesktopBridgeState(
                        status = DesktopBridgeStatus.ERROR,
                        errorMessage = error.message ?: "无法启动本地桥。",
                    )
                }
            }
        }
    }

    @Synchronized
    fun rotatePairingCode() {
        val currentSessions = sessions ?: return
        publishPairingCode(currentSessions.issuePairingCode())
    }

    @Synchronized
    fun stop() {
        val currentServer = server
        val currentSessions = sessions
        server = null
        sessions = null
        stateStore.value = DesktopBridgeState()
        currentServer?.close()
        currentSessions?.stop()
    }

    private fun publishPairingCode(code: PairingCode) {
        stateStore.value = DesktopBridgeState(
            status = DesktopBridgeStatus.WAITING_FOR_CODE,
            pairingCode = code.value,
            pairingCodeExpiresAt = code.expiresAt,
        )
        expiryExecutor.schedule(
            {
                stateStore.update { current ->
                    if (current.pairingCodeExpiresAt == code.expiresAt) {
                        current.copy(
                            pairingCode = null,
                            pairingCodeExpiresAt = null,
                        )
                    } else {
                        current
                    }
                }
            },
            BridgeLimits.PAIRING_CODE_TTL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    companion object {
        @Volatile
        private var instance: DesktopBridgeController? = null

        fun from(context: Context): DesktopBridgeController =
            instance ?: synchronized(this) {
                instance ?: DesktopBridgeController(context).also { instance = it }
            }
    }
}
