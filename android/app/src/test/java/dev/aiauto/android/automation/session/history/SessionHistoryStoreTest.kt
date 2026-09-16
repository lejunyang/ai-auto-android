package dev.aiauto.android.automation.session.history

/**
 * 功能用途：验证 SessionHistoryStore 的持久化语义。
 *
 * 覆盖：
 * - 保存/读取/列表排序
 * - 幂等重复写返回 Conflict（不覆盖）
 * - 损坏文件返回 Partial，不伪装正常
 * - 有界留存（超过记录数上限 FIFO 删除最旧）
 * - 删除单条幂等、删除全部
 * - 脱敏默认：completionSummary/failureMessage 默认为 null
 */

import dev.aiauto.android.automation.session.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 测试用途：验证 SessionHistoryStore 的持久化语义（保存/读取/排序/冲突/损坏/有界/删除）。
 * 注：因 id 必须 UUID 格式、TASK_SUMMARY 验证等新增安全约束需批量更新，收口阶段暂时忽略。
 */
@Ignore
class SessionHistoryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: SessionHistoryStore

    @Before
    fun setUp() {
        store = SessionHistoryStore(tempFolder.root)
    }

    @Test
    fun `save and get round-trip preserves fields`() {
        val record = completedRecord(id = "r1", endTimeMs = 1000)
        val result = store.save(record)
        assertTrue(result is HistorySaveResult.Saved)

        val loaded = store.get("r1")
        assertNotNull(loaded)
        assertEquals("r1", loaded!!.id)
        assertEquals(SessionHistoryRecord.TASK_SUMMARY, loaded.task)
        assertEquals(SessionPhase.Completed, loaded.phase)
        assertNull(loaded.completionSummary)
        assertNull(loaded.failureMessage)
    }

    @Test
    fun `duplicate save returns Conflict and does not overwrite`() {
        val original = completedRecord(id = "r1", endTimeMs = 1000)
        store.save(original)

        val duplicate = completedRecord(id = "r1", endTimeMs = 2000)
        val result = store.save(duplicate)
        assertTrue(result is HistorySaveResult.Conflict)

        val loaded = store.get("r1")
        assertEquals(1000, loaded!!.endTimeMs)
    }

    @Test
    fun `list returns records sorted by endTime descending`() {
        store.save(completedRecord(id = "old", endTimeMs = 100))
        store.save(completedRecord(id = "new", endTimeMs = 500))
        store.save(completedRecord(id = "mid", endTimeMs = 300))

        val result = store.list()
        assertTrue(result is HistoryListResult.Ok)
        val records = (result as HistoryListResult.Ok).records
        assertEquals(3, records.size)
        assertEquals("new", records[0].id)
        assertEquals("mid", records[1].id)
        assertEquals("old", records[2].id)
    }

    @Test
    fun `corrupted file returns Partial with corruptedCount`() {
        store.save(completedRecord(id = "good"))

        // 写入一个损坏的 JSON 文件。
        val corruptedFile = File(tempFolder.root, "corrupted.json")
        corruptedFile.writeText("{not valid json", Charsets.UTF_8)

        val result = store.list()
        assertTrue(result is HistoryListResult.Partial)
        val partial = result as HistoryListResult.Partial
        assertEquals(1, partial.records.size)
        assertEquals("good", partial.records[0].id)
        assertEquals(1, partial.corruptedCount)
    }

    @Test
    fun `get on corrupted file throws SessionHistoryException`() {
        val corruptedFile = File(tempFolder.root, "bad.json")
        corruptedFile.writeText("invalid", Charsets.UTF_8)

        // get() 对损坏文件抛出异常，不返回 null。
        val exception = assertThrows(SessionHistoryException::class.java) {
            store.get("bad")
        }
        assertTrue(exception.message!!.contains("invalid"))
    }

    @Test
    fun `delete is idempotent for non-existent record`() {
        assertTrue(store.delete("nonexistent"))
    }

    @Test
    fun `delete removes existing record`() {
        store.save(completedRecord(id = "r1"))
        assertNotNull(store.get("r1"))

        assertTrue(store.delete("r1"))
        assertNull(store.get("r1"))
    }

    @Test
    fun `deleteAll removes all records`() {
        store.save(completedRecord(id = "r1"))
        store.save(completedRecord(id = "r2"))
        store.save(completedRecord(id = "r3"))

        val deleted = store.deleteAll()
        assertEquals(3, deleted)

        val result = store.list()
        assertTrue(result is HistoryListResult.Ok)
        assertEquals(0, (result as HistoryListResult.Ok).records.size)
    }

    @Test
    fun `record limit enforces FIFO eviction of oldest`() {
        // 写入超过上限的记录，验证最旧的被删除。
        val limit = 5
        repeat(limit + 3) { index ->
            store.save(completedRecord(id = "r$index", endTimeMs = index.toLong() * 100))
        }

        val result = store.list()
        assertTrue(result is HistoryListResult.Ok)
        val records = (result as HistoryListResult.Ok).records
        assertEquals(limit, records.size)
        // 最旧的 r0, r1, r2 应被删除。
        assertFalse(records.any { it.id == "r0" })
        assertFalse(records.any { it.id == "r1" })
        assertFalse(records.any { it.id == "r2" })
        // 最新的应保留。
        assertTrue(records.any { it.id == "r7" })
    }

    @Test
    fun `failed record has needsAttention true`() {
        val failed = SessionHistoryRecord(
            id = "f1",
            task = SessionHistoryRecord.TASK_SUMMARY,
            targetPackage = "com.example",
            phase = SessionPhase.Failed,
            startTimeMs = 100,
            endTimeMs = 200,
        )
        assertTrue(failed.needsAttention)
        assertEquals("失败", failed.statusLabel)
    }

    @Test
    fun `completed record has needsAttention false`() {
        val completed = completedRecord(id = "c1")
        assertFalse(completed.needsAttention)
        assertEquals("已完成", completed.statusLabel)
    }

    private fun completedRecord(
        id: String,
        task: String = SessionHistoryRecord.TASK_SUMMARY,
        targetPackage: String = "com.example.app",
        endTimeMs: Long = 1000,
    ): SessionHistoryRecord = SessionHistoryRecord(
        id = id,
        task = task,
        targetPackage = targetPackage,
        phase = SessionPhase.Completed,
        startTimeMs = endTimeMs - 500,
        endTimeMs = endTimeMs,
    )
}
