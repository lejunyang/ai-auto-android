package dev.aiauto.android.bridge

/**
 * 测试用途：验证 LAN adapter 复用 Bridge 严格请求、重放和 deadline，但完全隔离 loopback 认证。
 */

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import dev.aiauto.android.bridge.lan.LanSessionToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanBridgeRpcDispatcherTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-25T10:00:30Z"), ZoneOffset.UTC)
    private val observer = RecordingObserver()
    private val loopbackSessions = BridgeSessionManager(clock = clock, observer = observer)
    private val handler = RecordingHandler()
    private val token = LanSessionToken.derive(
        tokenBindingKey =
            "7b567eef0e3f848a88001cf540c628df8da0d1f2b7732975ccba364af051300d"
                .hexToBytes(),
        transcriptHash =
            "2514787ca15ed4adb68993ea37fd5933f1804f9e80b1b239886fe80b867ac9bf"
                .hexToBytes(),
        invitationId = "647d42e0-47c3-4ac8-9e7f-8f8bcf716c41",
        expiresAt = Instant.parse("2026-07-25T10:01:30Z"),
    )
    private val adapter = LanBridgeRpcDispatcher(
        methodHandler = handler,
        token = token,
    )

    @After
    fun tearDown() {
        adapter.close()
    }

    @Test
    fun `valid lan token dispatches business methods without hello or loopback session`() {
        val response = adapter.dispatch(request("device.info")).response.decoded()

        assertEquals("device.info", response.result().string("method"))
        assertEquals(1, handler.calls)
        assertEquals(0, observer.opened)
        assertEquals(0, observer.closed)
    }

    @Test
    fun `missing wrong hello and session open requests never reach business handler`() {
        val missing = adapter.dispatch(request("device.info", tokenValue = null))
        assertEquals("AUTH_REQUIRED", missing.response.decoded().errorCode())
        assertTrue(missing.closeSession)
        val invalid = adapter.dispatch(
            request("device.info", tokenValue = "A".repeat(43)),
        )
        assertEquals("AUTH_INVALID", invalid.response.decoded().errorCode())
        assertTrue(invalid.closeSession)
        assertEquals(
            "AUTH_INVALID",
            adapter.dispatch(request("rpc.hello", tokenValue = "A".repeat(43)))
                .response.decoded().errorCode(),
        )
        assertEquals(
            "PROTOCOL_ERROR",
            adapter.dispatch(request("rpc.hello")).response.decoded().errorCode(),
        )
        assertEquals(
            "PROTOCOL_ERROR",
            adapter.dispatch(
                request(
                    "session.open",
                    params = buildJsonObject {
                        put("pairingCode", "123456")
                        put("hostName", "must-not-open")
                    },
                ),
            ).response.decoded().errorCode(),
        )

        assertEquals(0, handler.calls)
        assertEquals(0, observer.opened)
        assertEquals(0, observer.closed)
    }

    @Test
    fun `request replay and deadline use existing bridge error responses`() {
        val requestId = UUID.randomUUID().toString()
        assertTrue(adapter.dispatch(request("device.info", requestId = requestId)).response.decoded()
            .containsKey("result"))
        assertEquals(
            "REQUEST_REPLAYED",
            adapter.dispatch(request("device.info", requestId = requestId))
                .response.decoded().errorCode(),
        )

        handler.delayMillis = 300
        val started = System.nanoTime()
        val deadline = adapter.dispatch(request("ui.snapshot", deadlineMs = 25)).response.decoded()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals("DEADLINE_EXCEEDED", deadline.errorCode())
        assertTrue("elapsed=$elapsedMillis", elapsedMillis < 250)
    }

    @Test
    fun `single session dispatch never overlaps business handlers`() {
        handler.block = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { adapter.dispatch(request("device.info")) }
            assertTrue(handler.entered.await(1, TimeUnit.SECONDS))
            val second = executor.submit { adapter.dispatch(request("ui.snapshot")) }
            Thread.sleep(50)
            assertEquals(1, handler.maximumConcurrentCalls.get())

            handler.block?.countDown()
            first.get(1, TimeUnit.SECONDS)
            second.get(1, TimeUnit.SECONDS)
            assertEquals(1, handler.maximumConcurrentCalls.get())
            assertEquals(listOf("device.info", "ui.snapshot"), handler.methods)
        } finally {
            handler.block?.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `auth expired response is fatal to lan transport`() {
        handler.failure = BridgeException(
            code = BridgeErrorCode.AUTH_EXPIRED,
            message = "The LAN bridge session token has expired.",
        )

        val result = adapter.dispatch(request("device.info"))

        assertEquals("AUTH_EXPIRED", result.response.decoded().errorCode())
        assertTrue(result.closeSession)
    }

    @Test
    fun `lan session close requests transport close without closing loopback manager`() {
        val result = adapter.dispatch(request("session.close"))

        assertTrue(result.closeSession)
        assertTrue(result.response.decoded().result().boolean("closed"))
        assertEquals(0, observer.opened)
        assertEquals(0, observer.closed)
        assertFalse(handler.methods.contains("session.close"))
    }

    @Test
    fun `business risk gate error is preserved without exposing lan authentication`() {
        handler.failure = BridgeException(
            code = BridgeErrorCode.ACTION_NOT_ALLOWED,
            message = "The requested action is outside the allowed risk policy.",
        )

        val response = adapter.dispatch(request("action.execute")).response.decoded()

        assertEquals("ACTION_NOT_ALLOWED", response.errorCode())
        assertEquals(1, handler.calls)
        assertFalse(response.toString().contains("OV7XA1pDgpzb3v7JGVz8Piza267wb_CIK3JUKuwIFHA"))
    }

    private fun request(
        method: String,
        params: JsonObject = buildJsonObject {},
        tokenValue: String? = "OV7XA1pDgpzb3v7JGVz8Piza267wb_CIK3JUKuwIFHA",
        requestId: String = UUID.randomUUID().toString(),
        deadlineMs: Int = 1_000,
    ): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", UUID.randomUUID().toString())
        put("requestId", requestId)
        put("protocolVersion", "1.0")
        put("method", method)
        put("params", params)
        put("deadlineMs", deadlineMs)
        tokenValue?.let { put("token", it) }
    }.toString()

    private fun String.decoded(): JsonObject = Json.parseToJsonElement(this).jsonObject

    private fun JsonObject.result(): JsonObject = getValue("result").jsonObject

    private fun JsonObject.errorCode(): String =
        getValue("error").jsonObject.getValue("data").jsonObject.string("code")

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.boolean(name: String): Boolean =
        getValue(name).jsonPrimitive.content.toBoolean()

    private class RecordingHandler : BridgeMethodHandler {
        var calls = 0
        var delayMillis = 0L
        var failure: BridgeException? = null
        var block: CountDownLatch? = null
        val entered = CountDownLatch(1)
        val concurrentCalls = AtomicInteger()
        val maximumConcurrentCalls = AtomicInteger()
        val methods = mutableListOf<String>()

        override fun capabilities(): List<BridgeCapability> = emptyList()

        override fun handle(method: String, params: JsonObject): JsonObject {
            val active = concurrentCalls.incrementAndGet()
            maximumConcurrentCalls.accumulateAndGet(active, ::maxOf)
            try {
                calls += 1
                methods += method
                entered.countDown()
                if (delayMillis > 0) Thread.sleep(delayMillis)
                block?.await(1, TimeUnit.SECONDS)
                failure?.let { throw it }
                return buildJsonObject { put("method", method) }
            } finally {
                concurrentCalls.decrementAndGet()
            }
        }
    }

    private class RecordingObserver : BridgeSessionObserver {
        var opened = 0
        var closed = 0

        override fun onSessionOpened(hostName: String, expiresAt: Instant) {
            opened += 1
        }

        override fun onSessionClosed() {
            closed += 1
        }
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
