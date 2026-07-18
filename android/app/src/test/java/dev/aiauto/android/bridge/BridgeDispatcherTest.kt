package dev.aiauto.android.bridge

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeDispatcherTest {
    private val json = Json
    private val clock = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC)
    private val sessions = BridgeSessionManager(clock = clock)
    private val handler = RecordingHandler()
    private val dispatcher = BridgeDispatcher(
        methodHandler = handler,
        sessionManager = sessions,
        replayCache = RequestReplayCache(clock),
    )
    private val connection = BridgeConnectionState()

    @Test
    fun `hello negotiates version and advertises capabilities`() {
        val response = dispatch(
            method = "rpc.hello",
            params = helloParams(listOf("1.0")),
        )

        assertEquals("1.0", response.result()["selectedProtocolVersion"]?.jsonPrimitive?.content)
        assertTrue(connection.helloCompleted)
        assertEquals("1.0", connection.protocolVersion)
    }

    @Test
    fun `major mismatch is rejected and hello remains incomplete`() {
        val response = dispatch(
            method = "rpc.hello",
            params = helloParams(listOf("2.0")),
        )

        assertEquals("VERSION_INCOMPATIBLE", response.errorCode())
        assertFalse(connection.helloCompleted)
    }

    @Test
    fun `session open exchanges code for token without echoing code`() {
        hello()
        val code = sessions.issuePairingCode()

        val response = dispatch(
            method = "session.open",
            params = buildJsonObject {
                put("pairingCode", code.value)
                put("hostName", "test-host")
            },
        )

        val token = response.result()["token"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(token.length >= 32)
        assertFalse(response.toString().contains(code.value))
    }

    @Test
    fun `protected method rejects missing wrong and replayed token`() {
        hello()
        val token = openSession()

        assertEquals(
            "AUTH_REQUIRED",
            dispatch("device.info", buildJsonObject {}, token = null).errorCode(),
        )
        assertEquals(
            "AUTH_INVALID",
            dispatch(
                "device.info",
                buildJsonObject {},
                token = "different-token-that-is-at-least-32-bytes",
            ).errorCode(),
        )

        val requestId = UUID.randomUUID().toString()
        assertTrue(
            dispatch(
                "device.info",
                buildJsonObject {},
                token,
                requestId,
            ).containsKey("result"),
        )
        assertEquals(
            "REQUEST_REPLAYED",
            dispatch(
                "device.info",
                buildJsonObject {},
                token,
                requestId,
            ).errorCode(),
        )
    }

    @Test
    fun `session close revokes token`() {
        hello()
        val token = openSession()
        val requestId = UUID.randomUUID().toString()

        assertEquals(
            true,
            dispatch("session.close", buildJsonObject {}, token, requestId)
                .result()["closed"]?.jsonPrimitive?.content?.toBoolean(),
        )
        assertEquals(
            "REQUEST_REPLAYED",
            dispatch("session.close", buildJsonObject {}, token, requestId).errorCode(),
        )
    }

    @Test
    fun `oversized raw message returns stable error`() {
        val response = dispatcher.dispatch(
            "x".repeat(BridgeLimits.MAX_MESSAGE_BYTES),
            connection,
        ).decoded()

        assertEquals("MESSAGE_TOO_LARGE", response.errorCode())
    }

    @Test
    fun `method handler capability failure is preserved`() {
        hello()
        val token = openSession()
        handler.failure = BridgeException(
            code = BridgeErrorCode.CAPABILITY_UNAVAILABLE,
            message = "Accessibility is unavailable.",
            retryable = true,
        )

        val response = dispatch("ui.snapshot", buildJsonObject {}, token)

        assertEquals("CAPABILITY_UNAVAILABLE", response.errorCode())
        assertEquals(
            true,
            response["error"]?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("retryable")?.jsonPrimitive
                ?.content?.toBoolean(),
        )
    }

    private fun hello() {
        val response = dispatch("rpc.hello", helloParams(listOf("1.0")))
        assertTrue(response.containsKey("result"))
    }

    private fun openSession(): String {
        val code = sessions.issuePairingCode()
        return dispatch(
            method = "session.open",
            params = buildJsonObject {
                put("pairingCode", code.value)
                put("hostName", "test-host")
            },
        ).result()["token"]!!.jsonPrimitive.content
    }

    private fun dispatch(
        method: String,
        params: JsonObject,
        token: String? = null,
        requestId: String = UUID.randomUUID().toString(),
    ): JsonObject {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", UUID.randomUUID().toString())
            put("requestId", requestId)
            put("protocolVersion", "1.0")
            put("method", method)
            put("params", params)
            put("deadlineMs", 1_000)
            token?.let { put("token", it) }
        }
        return dispatcher.dispatch(request.toString(), connection).decoded()
    }

    private fun helloParams(versions: List<String>): JsonObject = buildJsonObject {
        put("clientVersion", "0.1.0")
        put(
            "supportedProtocolVersions",
            kotlinx.serialization.json.buildJsonArray {
                versions.forEach { version -> add(JsonPrimitive(version)) }
            },
        )
        put("capabilities", kotlinx.serialization.json.buildJsonArray {})
    }

    private fun String.decoded(): JsonObject =
        json.parseToJsonElement(this).jsonObject

    private fun JsonObject.result(): JsonObject = getValue("result").jsonObject

    private fun JsonObject.errorCode(): String =
        getValue("error").jsonObject
            .getValue("data").jsonObject
            .getValue("code").jsonPrimitive.content

    private class RecordingHandler : BridgeMethodHandler {
        var failure: BridgeException? = null

        override fun capabilities(): List<BridgeCapability> = listOf(
            BridgeCapability(name = "device.info", available = true),
        )

        override fun handle(method: String, params: JsonObject): JsonObject {
            failure?.let { throw it }
            return buildJsonObject {
                put("method", method)
            }
        }
    }
}
