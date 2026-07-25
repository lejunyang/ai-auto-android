package dev.aiauto.android.bridge.lan

/**
 * 功能用途：按 N37 字节契约提供严格、有界且失败关闭的 LAN NDJSON socket 端口。
 */

import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class NdjsonLanSocketPort private constructor(
    private val socket: Socket,
    private val channel: SocketChannel?,
) : LanSocketPort {
    private val readLock = Any()
    private val writeLock = Any()
    private val isClosed = AtomicBoolean(false)

    constructor(socket: Socket) : this(socket, null)

    constructor(channel: SocketChannel) : this(channel.socket(), channel)

    val closed: Boolean
        get() = isClosed.get()

    override fun writeHandshake(message: JsonObject) {
        writeJson(message, MAX_HANDSHAKE_BYTES, "LAN_FRAME_TOO_LARGE")
    }

    override fun readHandshake(): JsonObject = synchronized(readLock) {
        ensureOpen()
        try {
            val payload = readBoundedLine(MAX_HANDSHAKE_BYTES)
            val text = decodeUtf8(payload)
            StrictJsonKeyScanner(text).validate()
            JSON.parseToJsonElement(text).jsonObject
        } catch (error: LanProtocolException) {
            close()
            throw error
        } catch (error: Exception) {
            close()
            throw LanProtocolException(
                "LAN_INVITATION_SCHEMA_INVALID",
                "LAN handshake JSON is invalid",
                error,
            )
        }
    }

    override fun writeEncrypted(frame: LanEncryptedFrame) {
        val message = buildJsonObject {
            put("version", frame.version)
            put("direction", frame.direction.wireValue)
            put("sequence", frame.sequence)
            put("type", frame.type)
            put("nonce", BASE64_URL.encodeToString(frame.nonce))
            put("ciphertext", BASE64_URL.encodeToString(frame.ciphertext))
        }
        writeJson(message, MAX_ENCRYPTED_TRANSPORT_BYTES, "LAN_FRAME_TOO_LARGE")
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            runCatching { channel?.close() }
            runCatching { socket.close() }
        }
    }

    private fun writeJson(message: JsonObject, limit: Int, tooLargeCode: String) =
        synchronized(writeLock) {
            ensureOpen()
            val payload = (message.toString() + "\n").encodeToByteArray()
            try {
                if (payload.size > limit) {
                    throw LanProtocolException(tooLargeCode)
                }
                socket.getOutputStream().apply {
                    write(payload)
                    flush()
                }
            } catch (error: LanProtocolException) {
                close()
                throw error
            } catch (error: Exception) {
                close()
                throw LanProtocolException(
                    "LAN_CONNECTION_FAILED",
                    "LAN NDJSON write failed",
                    error,
                )
            } finally {
                payload.fill(0)
            }
        }

    private fun readBoundedLine(limit: Int): ByteArray {
        val input = socket.getInputStream()
        val buffer = ByteArray(limit)
        var byteCount = 0
        var contentCount = 0
        try {
            while (true) {
                val next = input.read()
                if (next < 0) {
                    throw LanProtocolException("LAN_CONNECTION_CLOSED")
                }
                byteCount += 1
                if (byteCount > limit) {
                    throw LanProtocolException("LAN_FRAME_TOO_LARGE")
                }
                if (next == '\n'.code) {
                    if (contentCount > 0 && buffer[contentCount - 1] == '\r'.code.toByte()) {
                        throw LanProtocolException("LAN_INVITATION_SCHEMA_INVALID")
                    }
                    return buffer.copyOf(contentCount)
                }
                buffer[contentCount] = next.toByte()
                contentCount += 1
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun decodeUtf8(payload: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    } catch (error: Exception) {
        throw LanProtocolException(
            "LAN_INVITATION_SCHEMA_INVALID",
            "LAN handshake is not valid UTF-8",
            error,
        )
    } finally {
        payload.fill(0)
    }

    private fun ensureOpen() {
        if (isClosed.get() || socket.isClosed) {
            throw LanProtocolException("LAN_CONNECTION_CLOSED")
        }
    }

    private companion object {
        const val MAX_HANDSHAKE_BYTES = 64 * 1024
        const val MAX_ENCRYPTED_TRANSPORT_BYTES = 1024 * 1024 + 64 * 1024
        val BASE64_URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val JSON = Json {
            isLenient = false
            ignoreUnknownKeys = false
        }
    }
}

/**
 * 安全边界：在 kotlinx serialization 丢失重复键信息前，递归拒绝等价转义后的重复键。
 */
private class StrictJsonKeyScanner(
    private val source: String,
) {
    private var index = 0

    fun validate() {
        skipWhitespace()
        parseValue()
        skipWhitespace()
        if (index != source.length) fail()
    }

    private fun parseValue() {
        skipWhitespace()
        when (source.getOrNull(index)) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            null -> fail()
            else -> parsePrimitive()
        }
    }

    private fun parseObject() {
        expect('{')
        skipWhitespace()
        if (take('}')) return
        val keys = mutableSetOf<String>()
        while (true) {
            skipWhitespace()
            if (source.getOrNull(index) != '"') fail()
            val key = parseString()
            if (!keys.add(key)) fail()
            skipWhitespace()
            expect(':')
            parseValue()
            skipWhitespace()
            if (take('}')) return
            expect(',')
        }
    }

    private fun parseArray() {
        expect('[')
        skipWhitespace()
        if (take(']')) return
        while (true) {
            parseValue()
            skipWhitespace()
            if (take(']')) return
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val value = StringBuilder()
        while (true) {
            val next = source.getOrNull(index++) ?: fail()
            when (next) {
                '"' -> return value.toString()
                '\\' -> value.append(parseEscape())
                else -> {
                    if (next.code < 0x20) fail()
                    value.append(next)
                }
            }
        }
    }

    private fun parseEscape(): Char = when (val escaped = source.getOrNull(index++) ?: fail()) {
        '"', '\\', '/' -> escaped
        'b' -> '\b'
        'f' -> '\u000c'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            val end = index + 4
            if (end > source.length) fail()
            val code = source.substring(index, end).toIntOrNull(16) ?: fail()
            index = end
            code.toChar()
        }
        else -> fail()
    }

    private fun parsePrimitive() {
        val start = index
        while (index < source.length && source[index] !in VALUE_DELIMITERS) {
            index += 1
        }
        if (index == start) fail()
    }

    private fun skipWhitespace() {
        while (source.getOrNull(index) in WHITESPACE) index += 1
    }

    private fun expect(expected: Char) {
        if (!take(expected)) fail()
    }

    private fun take(expected: Char): Boolean {
        if (source.getOrNull(index) != expected) return false
        index += 1
        return true
    }

    private fun fail(): Nothing =
        throw LanProtocolException("LAN_INVITATION_SCHEMA_INVALID")

    private companion object {
        val WHITESPACE = setOf(' ', '\t', '\n', '\r')
        val VALUE_DELIMITERS = WHITESPACE + setOf(',', ']', '}')
    }
}
