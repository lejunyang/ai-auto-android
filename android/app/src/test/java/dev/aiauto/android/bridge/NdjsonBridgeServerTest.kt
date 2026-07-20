package dev.aiauto.android.bridge

/**
 * 测试用途：验证 NdjsonBridgeServer 的功能契约、失败语义及自动化安全边界。
 */

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdjsonBridgeServerTest {
    private val sessions = BridgeSessionManager()
    private val handler = SocketTestHandler()
    private val dispatcher = BridgeDispatcher(handler, sessions)
    private val server = NdjsonBridgeServer(dispatcher, port = 0)

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `binds explicitly to IPv4 loopback and exchanges NDJSON`() {
        server.start()

        assertTrue(server.localAddress?.isLoopbackAddress == true)
        assertEquals("127.0.0.1", server.localAddress?.hostAddress)

        Socket("127.0.0.1", server.localPort).use { socket ->
            writeLine(socket, request("rpc.hello", helloParams()))
            val response = readLine(socket)
            assertEquals("1.0", response.result()["selectedProtocolVersion"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `rejects oversized message and closes connection`() {
        server.start()

        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().apply {
                write(ByteArray(BridgeLimits.MAX_MESSAGE_BYTES) { 'x'.code.toByte() })
                write('\n'.code)
                flush()
            }
            assertEquals("MESSAGE_TOO_LARGE", readLine(socket).errorCode())
            assertEquals(-1, socket.getInputStream().read())
        }
    }

    @Test
    fun `rejects a valid JSON object without an NDJSON newline`() {
        server.start()

        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().apply {
                write(request("rpc.hello", helloParams()).toByteArray(StandardCharsets.UTF_8))
                flush()
            }
            socket.shutdownOutput()

            assertEquals("PROTOCOL_ERROR", readLine(socket).errorCode())
        }
    }

    @Test
    fun `deadline returns stable error without waiting for handler completion`() {
        handler.delayMillis = 300
        server.start()

        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 3_000
            writeLine(socket, request("rpc.hello", helloParams()))
            assertNotNull(readLine(socket)["result"])
            val token = openSession(socket)

            val started = System.nanoTime()
            writeLine(
                socket,
                request(
                    method = "device.info",
                    params = buildJsonObject {},
                    token = token,
                    deadlineMs = 25,
                ),
            )
            assertEquals("DEADLINE_EXCEEDED", readLine(socket).errorCode())
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("elapsed=$elapsedMillis", elapsedMillis < 250)
        }
    }

    @Test
    fun `concurrency limit rejects excess request across connections`() {
        handler.block = CountDownLatch(1)
        server.start()
        val clients = mutableListOf<TestClient>()
        try {
            repeat(BridgeLimits.MAX_CONCURRENT_REQUESTS + 1) {
                TestClient(server.localPort).also { client ->
                    clients += client
                    client.hello()
                }
            }
            val token = clients.first().open()
            repeat(BridgeLimits.MAX_CONCURRENT_REQUESTS) {
                val client = clients[it]
                client.write(
                    request(
                        "device.info",
                        buildJsonObject {},
                        token,
                        deadlineMs = 2_000,
                    ),
                )
            }
            assertTrue(handler.started.await(2, TimeUnit.SECONDS))

            val excess = clients.last()
            excess.write(
                request(
                    "device.info",
                    buildJsonObject {},
                    token,
                    deadlineMs = 2_000,
                ),
            )
            assertEquals("RATE_LIMITED", excess.read().errorCode())
        } finally {
            handler.block?.countDown()
            clients.forEach(TestClient::close)
        }
    }

    @Test
    fun `connection limit rejects an excess idle client`() {
        server.start()
        val clients = mutableListOf<TestClient>()
        try {
            repeat(BridgeLimits.MAX_CONNECTIONS) {
                TestClient(server.localPort).also { client ->
                    clients += client
                    client.hello()
                }
            }

            val excess = TestClient(server.localPort)
            clients += excess
            assertEquals("RATE_LIMITED", excess.read().errorCode())
        } finally {
            clients.forEach(TestClient::close)
        }
    }

    @Test
    fun `client disconnect does not stop future connections`() {
        server.start()
        Socket("127.0.0.1", server.localPort).close()

        Socket("127.0.0.1", server.localPort).use { socket ->
            writeLine(socket, request("rpc.hello", helloParams()))
            assertNotNull(readLine(socket)["result"])
        }
    }

    private fun openSession(socket: Socket): String {
        val code = sessions.issuePairingCode()
        writeLine(
            socket,
            request(
                method = "session.open",
                params = buildJsonObject {
                    put("pairingCode", code.value)
                    put("hostName", "socket-test")
                },
            ),
        )
        return readLine(socket).result()["token"]!!.jsonPrimitive.content
    }

    private fun request(
        method: String,
        params: JsonObject,
        token: String? = null,
        deadlineMs: Int = 1_000,
    ): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", UUID.randomUUID().toString())
        put("requestId", UUID.randomUUID().toString())
        put("protocolVersion", "1.0")
        put("method", method)
        put("params", params)
        put("deadlineMs", deadlineMs)
        token?.let { put("token", it) }
    }.toString()

    private fun helloParams(): JsonObject = buildJsonObject {
        put("clientVersion", "0.1.0")
        put(
            "supportedProtocolVersions",
            buildJsonArray { add(JsonPrimitive("1.0")) },
        )
        put("capabilities", buildJsonArray {})
    }

    private fun writeLine(socket: Socket, value: String) {
        socket.getOutputStream().apply {
            write((value + "\n").toByteArray(StandardCharsets.UTF_8))
            flush()
        }
    }

    private fun readLine(socket: Socket): JsonObject {
        val line = BufferedReader(
            InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
        ).readLine()
        return Json.parseToJsonElement(line).jsonObject
    }

    private fun JsonObject.result(): JsonObject = getValue("result").jsonObject

    private fun JsonObject.errorCode(): String =
        getValue("error").jsonObject
            .getValue("data").jsonObject
            .getValue("code").jsonPrimitive.content

    private inner class TestClient(port: Int) : AutoCloseable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 3_000 }

        fun hello() {
            write(request("rpc.hello", helloParams()))
            read()
        }

        fun open(): String = openSession(socket)

        fun write(value: String) = writeLine(socket, value)

        fun read(): JsonObject = readLine(socket)

        override fun close() = socket.close()
    }

    private class SocketTestHandler : BridgeMethodHandler {
        var delayMillis = 0L
        var block: CountDownLatch? = null
        val started = CountDownLatch(BridgeLimits.MAX_CONCURRENT_REQUESTS)

        override fun capabilities(): List<BridgeCapability> = emptyList()

        override fun handle(method: String, params: JsonObject): JsonObject {
            started.countDown()
            if (delayMillis > 0) {
                Thread.sleep(delayMillis)
            }
            block?.await(5, TimeUnit.SECONDS)
            return buildJsonObject { put("ok", true) }
        }
    }
}
