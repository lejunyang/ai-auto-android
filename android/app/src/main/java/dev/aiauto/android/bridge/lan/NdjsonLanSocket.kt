package dev.aiauto.android.bridge.lan

/**
 * 功能用途：按 N37 字节契约提供严格、有界且失败关闭的 LAN NDJSON socket 端口。
 */

import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class NdjsonLanSocketPort private constructor(
    private val socket: Socket,
    private val channel: SocketChannel?,
) : LanSocketPort {
    private val readLock = Any()
    private val writeLock = Any()
    private val isClosed = AtomicBoolean(false)
    private val encryptedReadBuffer = ByteArray(MAX_ENCRYPTED_TRANSPORT_BYTES)
    private var encryptedReadCount = 0

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

    override fun readEncrypted(timeoutMillis: Int): LanEncryptedFrame? = synchronized(readLock) {
        ensureOpen()
        require(timeoutMillis > 0)
        try {
            val payload = readBoundedEncryptedLine(timeoutMillis) ?: return@synchronized null
            val text = try {
                decodeUtf8(payload)
            } catch (error: LanProtocolException) {
                throw LanProtocolException("LAN_FRAME_INVALID", cause = error)
            }
            try {
                StrictJsonKeyScanner(text).validate()
            } catch (error: LanProtocolException) {
                throw LanProtocolException("LAN_FRAME_INVALID", cause = error)
            }
            parseEncrypted(JSON.parseToJsonElement(text).jsonObject)
        } catch (_: SocketTimeoutException) {
            null
        } catch (error: java.io.IOException) {
            close()
            throw LanProtocolException(
                "LAN_CONNECTION_CLOSED",
                "encrypted LAN socket read failed",
                error,
            )
        } catch (error: LanProtocolException) {
            close()
            throw error
        } catch (error: Exception) {
            val connectionWasClosed = isClosed.get() || socket.isClosed
            close()
            if (connectionWasClosed) {
                throw LanProtocolException(
                    "LAN_CONNECTION_CLOSED",
                    "encrypted LAN socket was closed",
                    error,
                )
            }
            throw LanProtocolException(
                "LAN_FRAME_INVALID",
                "encrypted LAN frame JSON is invalid",
                error,
            )
        }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            runCatching { channel?.close() }
            runCatching { socket.close() }
            synchronized(readLock) {
                encryptedReadBuffer.fill(0)
                encryptedReadCount = 0
            }
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

    private fun readBoundedEncryptedLine(timeoutMillis: Int): ByteArray? {
        val input = socket.getInputStream()
        val deadline = System.nanoTime() +
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
        while (true) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return null
            socket.soTimeout = java.util.concurrent.TimeUnit.NANOSECONDS
                .toMillis(remainingNanos)
                .coerceAtLeast(1)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val next = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                return null
            }
            if (next < 0) {
                throw LanProtocolException("LAN_CONNECTION_CLOSED")
            }
            if (next == '\n'.code) {
                if (
                    encryptedReadCount > 0 &&
                    encryptedReadBuffer[encryptedReadCount - 1] == '\r'.code.toByte()
                ) {
                    throw LanProtocolException("LAN_FRAME_INVALID")
                }
                val payload = encryptedReadBuffer.copyOf(encryptedReadCount)
                encryptedReadBuffer.fill(0, 0, encryptedReadCount)
                encryptedReadCount = 0
                return payload
            }
            if (encryptedReadCount >= MAX_ENCRYPTED_TRANSPORT_BYTES - 1) {
                throw LanProtocolException("LAN_FRAME_TOO_LARGE")
            }
            encryptedReadBuffer[encryptedReadCount] = next.toByte()
            encryptedReadCount += 1
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

    private fun parseEncrypted(message: JsonObject): LanEncryptedFrame {
        if (message.keys != ENCRYPTED_FRAME_KEYS) {
            throw LanProtocolException("LAN_FRAME_INVALID")
        }
        val version = message.strictString("version")
        val direction = when (message.strictString("direction")) {
            LanFrameDirection.CLIENT_TO_DESKTOP.wireValue ->
                LanFrameDirection.CLIENT_TO_DESKTOP
            LanFrameDirection.DESKTOP_TO_CLIENT.wireValue ->
                LanFrameDirection.DESKTOP_TO_CLIENT
            else -> throw LanProtocolException("LAN_FRAME_INVALID")
        }
        val sequence = message.getValue("sequence").jsonPrimitive.longOrNull
            ?.takeIf { it >= 0 }
            ?: throw LanProtocolException("LAN_FRAME_INVALID")
        val type = message.strictString("type")
        val nonce = decodeBase64Url(message.strictString("nonce"), MAX_NONCE_BYTES)
        val ciphertext = try {
            decodeBase64Url(
                message.strictString("ciphertext"),
                LanFrameCodec.MAX_FRAME_CIPHERTEXT_BYTES,
            )
        } catch (error: Exception) {
            nonce.fill(0)
            throw error
        }
        if (nonce.size != GCM_NONCE_BYTES || ciphertext.size < GCM_TAG_BYTES) {
            nonce.fill(0)
            ciphertext.fill(0)
            throw LanProtocolException("LAN_FRAME_INVALID")
        }
        return LanEncryptedFrame(
            version = version,
            direction = direction,
            sequence = sequence,
            type = type,
            nonce = nonce,
            ciphertext = ciphertext,
        )
    }

    private fun JsonObject.strictString(name: String): String {
        val primitive = get(name) as? JsonPrimitive
            ?: throw LanProtocolException("LAN_FRAME_INVALID")
        if (!primitive.isString) throw LanProtocolException("LAN_FRAME_INVALID")
        return primitive.content
    }

    private fun decodeBase64Url(value: String, maxBytes: Int): ByteArray {
        if (
            value.isEmpty() ||
            value.length > encodedLength(maxBytes) ||
            !BASE64_URL_PATTERN.matches(value)
        ) {
            throw LanProtocolException("LAN_FRAME_INVALID")
        }
        return try {
            BASE64_URL_DECODER.decode(value).also { decoded ->
                if (
                    decoded.size > maxBytes ||
                    BASE64_URL.encodeToString(decoded) != value
                ) {
                    val tooLarge = decoded.size > maxBytes
                    decoded.fill(0)
                    throw LanProtocolException(
                        if (tooLarge) "LAN_FRAME_TOO_LARGE" else "LAN_FRAME_INVALID",
                    )
                }
            }
        } catch (error: IllegalArgumentException) {
            throw LanProtocolException("LAN_FRAME_INVALID", cause = error)
        }
    }

    private fun encodedLength(bytes: Int): Int = ((bytes + 2L) / 3L * 4L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    private companion object {
        const val MAX_HANDSHAKE_BYTES = 64 * 1024
        const val MAX_ENCRYPTED_TRANSPORT_BYTES = 1024 * 1024 + 64 * 1024
        const val MAX_NONCE_BYTES = 12
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
        val ENCRYPTED_FRAME_KEYS =
            setOf("version", "direction", "sequence", "type", "nonce", "ciphertext")
        val BASE64_URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val BASE64_URL_DECODER: Base64.Decoder = Base64.getUrlDecoder()
        val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")
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
