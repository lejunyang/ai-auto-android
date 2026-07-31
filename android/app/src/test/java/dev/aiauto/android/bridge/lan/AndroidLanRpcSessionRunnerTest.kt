package dev.aiauto.android.bridge.lan

/**
 * 测试用途：验证生产 LAN runner 自动启动 RPC，并允许显式停止后重新建立新的短期 session。
 */

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import dev.aiauto.android.bridge.BridgeMethodHandler
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLanRpcSessionRunnerTest {
    @Test
    fun `close interrupts active session and runner can serve a later session`() {
        val handler = mockk<BridgeMethodHandler>()
        val first = mockk<LanOutboundSession>(relaxed = true)
        val second = mockk<LanOutboundSession>(relaxed = true)
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val runner = AndroidLanRpcSessionRunner(
            dispatcherFactory = { handler },
            serve = { session, _ ->
                when (session) {
                    first -> {
                        firstEntered.countDown()
                        releaseFirst.await()
                    }

                    second -> {
                        secondEntered.countDown()
                        releaseSecond.await()
                    }
                }
            },
        )

        try {
            runner.start(first)
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
            runner.close()
            verify(atLeast = 1) { first.close() }

            runner.start(second)
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
            runner.close()
            verify(atLeast = 1) { second.close() }
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            runner.close()
        }
    }
}
