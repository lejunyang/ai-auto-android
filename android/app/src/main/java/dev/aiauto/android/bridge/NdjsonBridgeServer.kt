package dev.aiauto.android.bridge

/**
 * 功能用途：实现 NdjsonBridgeServer 对应的桌面端与 App 本地 Bridge 协议、认证或请求处理。
 */

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NdjsonBridgeServer(
    private val dispatcher: BridgeDispatcher,
    private val port: Int = BridgeLimits.PORT,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val connectionPermits = Semaphore(BridgeLimits.MAX_CONNECTIONS)
    private val openSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val acceptExecutor = Executors.newSingleThreadExecutor(
        namedThreadFactory("bridge-accept"),
    )
    private val connectionExecutor = Executors.newCachedThreadPool(
        namedThreadFactory("bridge-connection"),
    )
    private val requestExecutor = ThreadPoolExecutor(
        BridgeLimits.MAX_CONCURRENT_REQUESTS,
        BridgeLimits.MAX_CONCURRENT_REQUESTS,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        namedThreadFactory("bridge-request"),
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Volatile
    private var serverSocket: ServerSocket? = null

    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    val localAddress: InetAddress?
        get() = serverSocket?.inetAddress

    @Synchronized
    fun start() {
        check(!running.get()) { "Bridge server is already running" }
        // 服务只绑定设备 loopback；桌面端必须通过指定设备的 ADB forward 建立外层信任。
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(
                InetSocketAddress(loopback, port),
                BridgeLimits.MAX_CONNECTIONS,
            )
        } catch (error: Exception) {
            socket.close()
            throw error
        }
        serverSocket = socket
        running.set(true)
        acceptExecutor.execute(::acceptLoop)
    }

    override fun close() {
        if (!running.getAndSet(false)) {
            return
        }
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(openSockets) {
            openSockets.forEach { socket -> runCatching { socket.close() } }
            openSockets.clear()
        }
        acceptExecutor.shutdownNow()
        connectionExecutor.shutdownNow()
        requestExecutor.shutdownNow()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val client = try {
                serverSocket?.accept() ?: return
            } catch (_: SocketException) {
                return
            } catch (_: Exception) {
                if (!running.get()) return
                continue
            }
            if (!connectionPermits.tryAcquire()) {
                rejectConnection(client)
                continue
            }
            openSockets += client
            try {
                connectionExecutor.execute {
                    try {
                        handleConnection(client)
                    } finally {
                        openSockets -= client
                        runCatching { client.close() }
                        connectionPermits.release()
                    }
                }
            } catch (_: RejectedExecutionException) {
                openSockets -= client
                connectionPermits.release()
                rejectConnection(client)
            }
        }
    }

    private fun rejectConnection(client: Socket) {
        runCatching {
            client.use {
                writeLine(
                    it,
                    dispatcher.genericFailure(
                        BridgeException(
                            code = BridgeErrorCode.RATE_LIMITED,
                            message = "The bridge connection limit has been reached.",
                            retryable = true,
                        ),
                    ),
                )
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = BridgeLimits.DEFAULT_DEADLINE_MS
        val connection = BridgeConnectionState()
        // 缓冲区属于连接而非单帧，保留同一次网络读取中预读的后续消息。
        BufferedSocketInput(socket).use { input ->
            while (running.get() && !socket.isClosed) {
                val message = try {
                    readBoundedLine(input)
                } catch (_: SocketTimeoutException) {
                    writeLine(
                        socket,
                        dispatcher.genericFailure(
                            BridgeException(
                                code = BridgeErrorCode.DEADLINE_EXCEEDED,
                                message = "The NDJSON frame exceeded its read deadline.",
                                retryable = true,
                            ),
                        ),
                    )
                    return
                } catch (_: Exception) {
                    return
                }
                when (message) {
                    BoundedLine.End -> return
                    BoundedLine.Oversized -> {
                        writeLine(
                            socket,
                            dispatcher.genericFailure(
                                BridgeException(
                                    code = BridgeErrorCode.MESSAGE_TOO_LARGE,
                                    message = "The NDJSON message exceeds the 1048576 byte limit.",
                                ),
                            ),
                        )
                        return
                    }

                    BoundedLine.MalformedUtf8 -> {
                        writeLine(
                            socket,
                            dispatcher.genericFailure(
                                BridgeException(
                                    code = BridgeErrorCode.PROTOCOL_ERROR,
                                    message = "The NDJSON message is not valid UTF-8.",
                                ),
                            ),
                        )
                        return
                    }

                    BoundedLine.Truncated -> {
                        writeLine(
                            socket,
                            dispatcher.genericFailure(
                                BridgeException(
                                    code = BridgeErrorCode.PROTOCOL_ERROR,
                                    message = "The NDJSON message must end with a newline.",
                                ),
                            ),
                        )
                        return
                    }

                    is BoundedLine.Value -> dispatch(socket, message.text, connection)
                }
            }
        }
    }

    private fun dispatch(
        socket: Socket,
        rawMessage: String,
        connection: BridgeConnectionState,
    ) {
        val future = try {
            requestExecutor.submit<String> {
                dispatcher.dispatch(rawMessage, connection)
            }
        } catch (_: RejectedExecutionException) {
            writeLine(
                socket,
                dispatcher.failureResponse(
                    rawMessage,
                    BridgeException(
                        code = BridgeErrorCode.RATE_LIMITED,
                        message = "The bridge request concurrency limit has been reached.",
                        retryable = true,
                    ),
                ),
            )
            return
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
            return
        } catch (_: ExecutionException) {
            dispatcher.failureResponse(
                rawMessage,
                BridgeException(
                    code = BridgeErrorCode.INTERNAL_ERROR,
                    message = "The bridge could not complete the request.",
                ),
            )
        }
        writeLine(socket, response)
    }

    private fun writeLine(socket: Socket, response: String) {
        val bytes = (response + "\n").toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > BridgeLimits.MAX_MESSAGE_BYTES) {
            return
        }
        runCatching {
            socket.getOutputStream().apply {
                write(bytes)
                flush()
            }
        }
    }

    private fun readBoundedLine(input: BufferedSocketInput): BoundedLine {
        val output = ByteArrayOutputStream()
        var byteCount = 0
        val expiresAt = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(BridgeLimits.DEFAULT_DEADLINE_MS.toLong())
        while (true) {
            if (System.nanoTime() >= expiresAt) {
                throw SocketTimeoutException("NDJSON frame deadline exceeded")
            }
            val next = input.read(expiresAt)
            if (next < 0) {
                if (byteCount == 0) {
                    return BoundedLine.End
                }
                return BoundedLine.Truncated
            }
            byteCount += 1
            // 在完整分配或解析 JSON 前执行字节上限，避免畸形帧消耗不受控内存。
            if (byteCount > BridgeLimits.MAX_MESSAGE_BYTES) {
                return BoundedLine.Oversized
            }
            if (next == '\n'.code) {
                return decodeLine(output.toByteArray())
            }
            output.write(next)
        }
    }

    /** 每连接复用固定缓冲；避免每个字节都触发 read/setsockopt 系统调用。 */
    private class BufferedSocketInput(private val socket: Socket) : java.io.Closeable {
        private val input = socket.getInputStream()
        private val buffer = ByteArray(8 * 1024)
        private var offset = 0
        private var size = 0

        fun read(expiresAt: Long): Int {
            if (offset == size) {
                val remainingNanos = expiresAt - System.nanoTime()
                if (remainingNanos <= 0) throw SocketTimeoutException("NDJSON frame deadline exceeded")
                socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                    .coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                size = input.read(buffer)
                offset = 0
                if (size <= 0) return -1
            }
            return buffer[offset++].toInt() and 0xff
        }

        override fun close() {
            // 缓冲可含类型化动作文本，连接退出后清零；socket 仍由连接 owner 关闭。
            buffer.fill(0)
            offset = 0
            size = 0
        }
    }

    private fun decodeLine(bytes: ByteArray): BoundedLine {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching {
            BoundedLine.Value(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        }.getOrElse {
            BoundedLine.MalformedUtf8
        }
    }

    private sealed interface BoundedLine {
        data class Value(val text: String) : BoundedLine

        data object End : BoundedLine

        data object Oversized : BoundedLine

        data object MalformedUtf8 : BoundedLine

        data object Truncated : BoundedLine
    }

    private companion object {
        fun namedThreadFactory(prefix: String): ThreadFactory {
            val threadNumber = AtomicInteger()
            return ThreadFactory { task ->
                Thread(task, "$prefix-${threadNumber.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }
    }
}
