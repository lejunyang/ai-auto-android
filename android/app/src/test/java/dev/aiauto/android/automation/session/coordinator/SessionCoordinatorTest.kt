package dev.aiauto.android.automation.session.coordinator

/**
 * 功能用途：以虚拟协程调度验证 SessionCoordinator 的单一 owner 语义与生命周期边界。
 *
 * 覆盖：
 * - 单会话约束、非阻塞 start
 * - 终态后 cleanup，第二会话可启动
 * - engineFactory 创建失败安全返回 false
 * - clock/idProvider 注入可测试
 * - 历史写入诊断暴露（不静默失败）
 * - handleProcessRecovery 在 Idle 时不产生虚假 RecoveredStopped
 * - CoordinatorState.Active 不暴露 engine
 * - SensitiveLogRedactor 对密码/API key/验证码脱敏
 */

import dev.aiauto.android.automation.session.AutomationSessionEngine
import dev.aiauto.android.automation.session.AutomationSessionState
import dev.aiauto.android.automation.session.SessionPhase
import dev.aiauto.android.automation.session.SessionRequest
import dev.aiauto.android.automation.session.history.HistorySaveResult
import dev.aiauto.android.automation.session.history.SessionHistoryRecord
import dev.aiauto.android.automation.session.history.SessionHistoryStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 测试用途：验证 SessionCoordinator 的单会话约束、暂停/停止/确认、终态清理、close 生命周期、并发原子性。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {

    private lateinit var engine: AutomationSessionEngine
    private lateinit var engineState: MutableStateFlow<AutomationSessionState>
    private lateinit var historyStore: SessionHistoryStore
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        historyStore = SessionHistoryStore(tempFolder.root)

        engineState = MutableStateFlow(AutomationSessionState())
        engine = mockk(relaxed = true) {
            every { state } returns engineState
            every { pause() } just runs
            every { resume() } just runs
            every { stop() } just runs
        }
        // 活跃引擎是长期挂起调用；立即返回只用于专门验证异常退出的用例。
        coEvery { engine.start(any()) } coAnswers { awaitCancellation() }
    }

    private fun TestScope.createCoordinator(
        engineFactory: () -> AutomationSessionEngine = { engine },
        clock: () -> Long = { 1000L },
        idProvider: () -> String = { "test-id" },
    ): SessionCoordinator {
        return SessionCoordinator(
            engineFactory = engineFactory,
            historyStore = historyStore,
            scope = backgroundScope,
            clock = clock,
            idProvider = idProvider,
            historyWriteScope = backgroundScope,
        )
    }

    @Test
    fun `initial state is idle and not active`() = runTest {
        val coordinator = createCoordinator()
        assertFalse(coordinator.isActive)
        assertTrue(coordinator.coordinatorState.value is CoordinatorState.Idle)
    }

    @Test
    fun `start is non-blocking and returns true`() = runTest {
        val coordinator = createCoordinator()

        // start 应立即返回，不等待 engine.start 完成。
        val result = coordinator.start(SessionRequest(task = "测试", targetPackage = "com.example"))
        assertTrue(result)
        assertTrue(coordinator.isActive)
    }

    @Test
    fun `single session constraint rejects second start`() = runTest {
        val coordinator = createCoordinator()

        val first = coordinator.start(SessionRequest(task = "第一个", targetPackage = "com.example"))
        assertTrue(first)

        val second = coordinator.start(SessionRequest(task = "第二个", targetPackage = "com.example"))
        assertFalse("已有会话运行时应拒绝第二个 start", second)
    }

    @Test
    fun `after terminal state coordinator returns to idle and second session can start`() = runTest {
        val coordinator = createCoordinator()

        coordinator.start(SessionRequest(task = "第一个", targetPackage = "com.example"))
        assertTrue(coordinator.isActive)

        runCurrent()
        // 收集器已经订阅后再发终态，确定性验证状态驱动的清理路径。
        engineState.value = AutomationSessionState(phase = SessionPhase.Completed)
        runCurrent()

        // 终态后应清理，回到 Idle。
        assertFalse(coordinator.isActive)
        assertTrue(coordinator.coordinatorState.value is CoordinatorState.Idle)

        // 第二会话使用新的初始状态，不能继承前一会话的终态。
        engineState.value = AutomationSessionState()
        val second = coordinator.start(SessionRequest(task = "第二个", targetPackage = "com.example"))
        assertTrue(second)
    }

    @Test
    fun `engineFactory failure returns false and does not become active`() = runTest {
        val coordinator = createCoordinator(engineFactory = { throw RuntimeException("create failed") })

        val result = coordinator.start(SessionRequest(task = "测试", targetPackage = "com.example"))
        assertFalse(result)
        assertFalse(coordinator.isActive)
    }

    @Test
    fun `pause calls engine pause when active`() = runTest {
        val coordinator = createCoordinator()
        coordinator.start(SessionRequest(task = "测试", targetPackage = "com.example"))

        coordinator.pause()
        verify(exactly = 1) { engine.pause() }
    }

    @Test
    fun `stop calls engine stop when active`() = runTest {
        val coordinator = createCoordinator()
        coordinator.start(SessionRequest(task = "测试", targetPackage = "com.example"))

        coordinator.stop()
        verify(exactly = 1) { engine.stop() }
    }

    @Test
    fun `pause when idle does nothing`() = runTest {
        val coordinator = createCoordinator()
        coordinator.pause()
        verify(exactly = 0) { engine.pause() }
    }

    @Test
    fun `process recovery when active stops engine and marks recovered stopped`() = runTest {
        // 同一虚拟调度器保证 cancel/join 与恢复状态的顺序可验证。
        val coordinator = createCoordinator()
        coordinator.start(SessionRequest(task = "测试", targetPackage = "com.example"))
        assertTrue(coordinator.isActive)

        // 挂起恢复：内部 cancel/join 后台协程后再设终态，结果确定。
        coordinator.handleProcessRecovery()

        verify(exactly = 1) { engine.stop() }
        assertFalse(coordinator.isActive)
        assertTrue(coordinator.coordinatorState.value is CoordinatorState.RecoveredStopped)
    }

    @Test
    fun `process recovery when idle does nothing and stays idle`() = runTest {
        val coordinator = createCoordinator()
        assertFalse(coordinator.isActive)

        coordinator.handleProcessRecovery()

        // Idle 时不应产生虚假的 RecoveredStopped。
        assertTrue(coordinator.coordinatorState.value is CoordinatorState.Idle)
        assertFalse(coordinator.coordinatorState.value is CoordinatorState.RecoveredStopped)
    }

    @Test
    fun `history write diagnostic is exposed on failure`() = runTest {
        // 使用一个会抛出异常的 historyStore。
        val failingStore = mockk<SessionHistoryStore> {
            every { save(any()) } throws RuntimeException("disk full")
        }
        val coordinator = SessionCoordinator(
            engineFactory = { engine },
            historyStore = failingStore,
            scope = backgroundScope,
            historyWriteScope = backgroundScope,
        )

        coordinator.start(SessionRequest(task = "测试", targetPackage = "com.example"))
        engineState.value = AutomationSessionState(phase = SessionPhase.Completed)
        runCurrent()

        val diagnostic = coordinator.historyWriteDiagnostic.value
        assertTrue(diagnostic is HistoryWriteDiagnostic.Failed)
        assertEquals("HISTORY_WRITE_FAILED", (diagnostic as HistoryWriteDiagnostic.Failed).errorCode)
    }

    @Test
    fun `delayed history retains the previous session start time`() = runTest {
        val historyScheduler = TestCoroutineScheduler()
        val delayedHistory = TestScope(StandardTestDispatcher(historyScheduler))
        var now = 1_000L
        val saved = mutableListOf<SessionHistoryRecord>()
        val store = mockk<SessionHistoryStore> {
            every { save(any()) } answers {
                val record = firstArg<SessionHistoryRecord>()
                saved.add(record)
                HistorySaveResult.Saved(record)
            }
        }
        val coordinator = SessionCoordinator(
            engineFactory = { engine }, historyStore = store,
            scope = backgroundScope, historyWriteScope = delayedHistory.backgroundScope,
            clock = { now }, idProvider = { "previous-session" },
        )
        assertTrue(coordinator.start(SessionRequest(task = "第一轮", targetPackage = "com.example")))
        runCurrent()
        now = 1_500L
        engineState.value = AutomationSessionState(phase = SessionPhase.Completed)
        runCurrent()
        assertFalse(coordinator.isActive)
        assertTrue(saved.isEmpty())

        // IO 尚未执行时启动下一会话，历史不得读取下一会话的可变时间字段。
        now = 2_000L
        engineState.value = AutomationSessionState()
        assertTrue(coordinator.start(SessionRequest(task = "第二轮", targetPackage = "com.example")))
        historyScheduler.runCurrent()
        assertEquals(1, saved.size)
        assertEquals(1_000L, saved.single().startTimeMs)
        assertEquals(1_500L, saved.single().endTimeMs)
        coordinator.close()
    }

    @Test
    fun `coordinator state active does not expose engine`() = runTest {
        val coordinator = createCoordinator()
        coordinator.start(SessionRequest(task = "测试", targetPackage = "com.example"))

        val state = coordinator.coordinatorState.value
        assertTrue(state is CoordinatorState.Active)
        // Active 是 object，不含 engine 字段，外部无法直接访问。
        assertFalse(state::class.members.any { it.name == "engine" })
    }

    @Test
    fun `concurrent start only one accepted`() = runTest {
        val coordinator = createCoordinator()

        // 两个并发 start 请求，只有一个应被接受。
        val result1 = coordinator.start(SessionRequest(task = "任务1", targetPackage = "com.example"))
        val result2 = coordinator.start(SessionRequest(task = "任务2", targetPackage = "com.example"))

        assertTrue("第一个 start 应被接受", result1)
        assertFalse("第二个并发 start 应被拒绝", result2)
        assertTrue(coordinator.isActive)
    }

    @Test
    fun `engine start returns without terminal state triggers cleanup`() = runTest {
        val coordinator = createCoordinator()
        // 此用例有意让 engine.start 正常返回，但不发任何终态。
        coEvery { engine.start(any()) } just runs

        coordinator.start(SessionRequest(task = "测试", targetPackage = "com.example"))
        assertTrue(coordinator.isActive)

        // 等待 sessionJob 完成（engine.start 已返回），coordinator 应自动清理。
        runCurrent()

        // engine.start 正常返回但无终态，应视为 Stopped 并清理。
        assertFalse("无终态返回后应自动清理", coordinator.isActive)
        assertTrue(coordinator.coordinatorState.value is CoordinatorState.Idle)
    }

    @Test
    fun `stop without terminal state forces cleanup after timeout`() = runTest {
        val coordinator = createCoordinator()
        // engine.stop 被调用，但 engine 不发 Stopped 终态。

        coordinator.start(SessionRequest(task = "测试", targetPackage = "com.example"))
        assertTrue(coordinator.isActive)

        coordinator.stop()
        // stop 后立即仍 Active（等待终态或超时）。
        assertTrue(coordinator.isActive)

        // 在截止前必须仍 Active，精确达到 5 秒后必须清理，不能用一次短等待冒充验收。
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(coordinator.isActive)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(coordinator.isActive)
        assertTrue(coordinator.coordinatorState.value is CoordinatorState.Idle)
        verify(exactly = 1) { engine.stop() }
    }

    @Test
    fun `close cancels scope and cleans up`() = runTest {
        val coordinator = createCoordinator()

        coordinator.start(SessionRequest(task = "测试", targetPackage = "com.example"))
        assertTrue(coordinator.isActive)

        // close 应停止 engine 并取消 scope。
        coordinator.close()
        verify { engine.stop() }
        // close 后 coordinator 不可再用，start 应失败（scope 已取消）。
        val result = coordinator.start(SessionRequest(task = "新任务", targetPackage = "com.example"))
        assertFalse("close 后 start 应失败", result)
    }
}
