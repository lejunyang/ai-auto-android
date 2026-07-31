package dev.aiauto.android.bridge

/**
 * 功能用途：为已完成 LAN handshake 的连接复用 Bridge 调度规则，同时隔离 loopback 认证状态。
 */

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

import dev.aiauto.android.bridge.lan.LanSessionToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class LanBridgeRpcResult(
    val response: String,
    val closeSession: Boolean,
)

class LanBridgeRpcDispatcher(
    methodHandler: BridgeMethodHandler,
    token: LanSessionToken,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val authority = LanBridgeSessionAuthority(token)
    private val dispatcher = BridgeDispatcher(
        methodHandler = methodHandler,
        sessionManager = authority,
    )
    private val connection = BridgeConnectionState(
        helloCompleted = true,
        protocolVersion = BridgeProtocol.VERSION,
    )
    private val executor = Executors.newSingleThreadExecutor(namedThreadFactory())

    @Synchronized
    fun dispatch(rawMessage: String): LanBridgeRpcResult {
        check(!closed.get()) { "LAN Bridge dispatcher is closed" }
        val future = executor.submit<String> {
            dispatcher.dispatch(rawMessage, connection)
        }
        val response = try {
            future.get(dispatcher.deadlineMs(rawMessage).toLong(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            dispatcher.failureResponse(
                rawMessage,
                BridgeException(
                    code = BridgeErrorCode.DEADLINE_EXCEEDED,
                    message = "The bridge request exceeded its deadline.",
                    retryable = true,
                ),
            )
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            dispatcher.failureResponse(
                rawMessage,
                BridgeException(
                    code = BridgeErrorCode.INTERNAL_ERROR,
                    message = "The bridge request was interrupted.",
                ),
            )
        } catch (_: Exception) {
            dispatcher.failureResponse(
                rawMessage,
                BridgeException(
                    code = BridgeErrorCode.INTERNAL_ERROR,
                    message = "The bridge could not complete the request.",
                ),
            )
        }
        return LanBridgeRpcResult(
            response = response,
            closeSession = authority.consumeCloseRequested() || response.hasFatalAuthError(),
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            authority.destroy()
            executor.shutdownNow()
        }
    }

    private fun String.hasFatalAuthError(): Boolean =
        runCatching {
            Json.parseToJsonElement(this).jsonObject
                .getValue("error").jsonObject
                .getValue("data").jsonObject
                .getValue("code").jsonPrimitive.content in FATAL_AUTH_CODES
        }.getOrDefault(false)

    private companion object {
        val FATAL_AUTH_CODES = setOf("AUTH_REQUIRED", "AUTH_INVALID", "AUTH_EXPIRED")

        fun namedThreadFactory(): ThreadFactory = ThreadFactory { task ->
            Thread(task, "lan-bridge-request").apply { isDaemon = true }
        }
    }
}

private class LanBridgeSessionAuthority(
    private val token: LanSessionToken,
) : BridgeSessionAuthority {
    override val transportAuthenticated: Boolean = true
    private var closeRequested = false

    override fun open(code: String, hostName: String): OpenedBridgeSession {
        throw BridgeException(
            code = BridgeErrorCode.PROTOCOL_ERROR,
            message = "LAN handshake already completed connection authentication.",
        )
    }

    override fun authenticate(token: String?) {
        if (token.isNullOrEmpty()) {
            throw BridgeException(
                code = BridgeErrorCode.AUTH_REQUIRED,
                message = "A current LAN bridge session token is required.",
            )
        }
        if (!this.token.matches(token)) {
            throw BridgeException(
                code = BridgeErrorCode.AUTH_INVALID,
                message = "The LAN bridge session token is invalid.",
            )
        }
    }

    override fun close(token: String?) {
        authenticate(token)
        closeRequested = true
    }

    fun consumeCloseRequested(): Boolean =
        closeRequested.also { closeRequested = false }

    fun destroy() {
        closeRequested = false
        token.close()
    }
}
