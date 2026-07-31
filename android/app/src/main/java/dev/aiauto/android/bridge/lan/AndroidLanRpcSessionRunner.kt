package dev.aiauto.android.bridge.lan

/**
 * 功能用途：为确认后的 Android LAN session 启动唯一后台 RPC 循环，并在替换或关闭时中断读取。
 */

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

import dev.aiauto.android.bridge.BridgeMethodHandler

internal class AndroidLanRpcSessionRunner(
    private val dispatcherFactory: () -> BridgeMethodHandler,
    private val executorFactory: () -> ExecutorService = {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "lan-bridge-rpc").apply { isDaemon = true }
        }
    },
    private val serve: (LanOutboundSession, BridgeMethodHandler) -> Unit =
        { session, handler -> session.serve(handler) },
) : AutoCloseable {
    private var activeSession: LanOutboundSession? = null
    private var activeExecutor: ExecutorService? = null
    private var activeTask: Future<*>? = null

    @Synchronized
    fun start(session: LanOutboundSession) {
        stopActive()
        val executor = executorFactory()
        activeSession = session
        activeExecutor = executor
        activeTask = try {
            executor.submit {
                try {
                    serve(session, dispatcherFactory())
                } catch (_: LanProtocolException) {
                    // 会话已按稳定错误码失败关闭；生产路径不得记录请求、token 或加密材料。
                } finally {
                    session.close()
                    synchronized(this) {
                        if (activeSession === session) {
                            activeSession = null
                            activeTask = null
                            activeExecutor = null
                        }
                    }
                    executor.shutdownNow()
                }
            }
        } catch (error: Exception) {
            activeSession = null
            activeExecutor = null
            session.close()
            executor.shutdownNow()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        stopActive()
    }

    private fun stopActive() {
        val session = activeSession
        val task = activeTask
        val executor = activeExecutor
        activeSession = null
        activeTask = null
        activeExecutor = null
        session?.close()
        task?.cancel(true)
        executor?.shutdownNow()
    }
}
