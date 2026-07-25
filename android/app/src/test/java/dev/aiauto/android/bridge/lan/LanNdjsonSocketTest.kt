package dev.aiauto.android.bridge.lan

/**
 * 测试用途：验证真实 localhost socket 与 N37 使用一致的有界 NDJSON 握手，并拒绝
 * 重复字段、超限消息或缺少完整元数据的加密 frame。
 */

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanNdjsonSocketTest {
    @Test
    fun `handshake reads fragmented strict json and writes one ndjson message`() {
        withSocketPair { client, server ->
            val port = NdjsonLanSocketPort(client)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val response = worker.submit(Callable {
                    val input = server.getInputStream()
                    val request = readLine(input)
                    val output = server.getOutputStream()
                    """{"type":"lan.desktopSelection","version":"1.0"}"""
                        .chunked(5)
                        .forEach {
                            output.write(it.encodeToByteArray())
                            output.flush()
                        }
                    output.write('\n'.code)
                    request
                })

                port.writeHandshake(
                    buildJsonObject {
                        put("type", "lan.clientHello")
                        put("version", "1.0")
                    },
                )
                val received = port.readHandshake()

                val sent = Json.parseToJsonElement(response.get()).jsonObject
                assertEquals("lan.clientHello", sent.string("type"))
                assertEquals("1.0", sent.string("version"))
                assertEquals("lan.desktopSelection", received.string("type"))
                assertEquals("1.0", received.string("version"))
            } finally {
                port.close()
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun `encrypted write includes complete n37 wire metadata and copies frame bytes`() {
        withSocketPair { client, server ->
            val port = NdjsonLanSocketPort(client)
            val nonce = ByteArray(12) { it.toByte() }
            val ciphertext = ByteArray(24) { (it + 32).toByte() }
            val frame = LanEncryptedFrame(
                version = "1.0",
                direction = LanFrameDirection.CLIENT_TO_DESKTOP,
                sequence = 7,
                type = "rpc",
                nonce = nonce,
                ciphertext = ciphertext,
            )
            try {
                port.writeEncrypted(frame)
                nonce.fill(0)
                ciphertext.fill(0)

                val wire = Json.parseToJsonElement(
                    readLine(server.getInputStream()),
                ).jsonObject
                assertEquals(
                    setOf("version", "direction", "sequence", "type", "nonce", "ciphertext"),
                    wire.keys,
                )
                assertEquals("1.0", wire.string("version"))
                assertEquals("client-to-desktop", wire.string("direction"))
                assertEquals(7L, wire.getValue("sequence").jsonPrimitive.content.toLong())
                assertEquals("rpc", wire.string("type"))
                assertFalse(wire.string("nonce").all { it == 'A' })
                assertFalse(wire.string("ciphertext").all { it == 'A' })
            } finally {
                frame.destroy()
                port.close()
            }
        }
    }

    @Test
    fun `duplicate keys and oversized handshake fail closed`() {
        listOf(
            """{"type":"one","type":"two"}""" to "LAN_INVITATION_SCHEMA_INVALID",
            """{"payload":"${"x".repeat(64 * 1024)}"}""" to "LAN_FRAME_TOO_LARGE",
        ).forEach { (payload, expectedCode) ->
            withSocketPair { client, server ->
                val port = NdjsonLanSocketPort(client)
                server.getOutputStream().apply {
                    write(payload.encodeToByteArray())
                    write('\n'.code)
                    flush()
                }

                assertFailure(expectedCode) {
                    port.readHandshake()
                }
                assertTrue(port.closed)
            }
        }
    }

    private fun withSocketPair(block: (Socket, Socket) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val client = Socket("127.0.0.1", listener.localPort)
            val server = listener.accept()
            client.use {
                server.use {
                    block(client, server)
                }
            }
        }
    }

    private fun readLine(input: java.io.InputStream): String {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val next = input.read()
            if (next < 0 || next == '\n'.code) break
            bytes += next.toByte()
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun assertFailure(expectedCode: String, action: () -> Unit) {
        try {
            action()
            fail("expected $expectedCode")
        } catch (error: LanProtocolException) {
            assertEquals(expectedCode, error.code)
        }
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.content
}
