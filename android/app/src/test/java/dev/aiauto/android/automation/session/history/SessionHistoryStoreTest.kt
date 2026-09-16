package dev.aiauto.android.automation.session.history

/**
 * 测试用途：验证 SessionHistoryStore 的持久化语义（保存/读取/排序/冲突/损坏/有界/删除）。
 *
 * 安全约束：id 必须 UUID 格式，task 必须为固定摘要 TASK_SUMMARY（不保存用户原始任务文本）。
 */

import dev.aiauto.android.automation.session.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * 测试用途：验证 SessionHistoryStore 的持久化语义（保存/读取/排序/冲突/损坏/有界/删除）。
 */
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
        val id = UUID.randomUUID().toString()
        val record = completedRecord(id = id, endTimeMs = 1000)
        val result = store.save(record)
        assertTrue(result is HistorySaveResult.Saved)

        val loaded = store.get(id)
        assertNotNull(loaded)
        assertEquals(id, loaded!!.id)
        assertEquals(SessionHistoryRecord.TASK_SUMMARY, loaded.task)
        assertEquals(SessionPhase.Completed, loaded.phase)
        assertNull(loaded.completionSummary)
        assertNull(loaded.failureMessage)
    }

    @Test
    fun `duplicate save returns Conflict and does not overwrite`() {
        val id = UUID.randomUUID().toString()
        val original = completedRecord(id = id, endTimeMs = 1000)
        store.save(original)

        val duplicate = completedRecord(id = id, endTimeMs = 2000)
        val result = store.save(duplicate)
        assertTrue(result is HistorySaveResult.Conflict)

        val loaded = store.get(id)
        assertEquals(1000, loaded!!.endTimeMs)
    }

    @Test
    fun `list returns records sorted by endTime descending`() {
        val oldId = UUID.randomUUID().toString()
        val newId = UUID.randomUUID().toString()
        val midId = UUID.randomUUID().toString()
        store.save(completedRecord(id = oldId, endTimeMs = 1000))
        store.save(completedRecord(id = newId, endTimeMs = 5000))
        store.save(completedRecord(id = midId, endTimeMs = 3000))

        val result = store.list()
        assertTrue(result is HistoryListResult.Ok)
        val records = (result as HistoryListResult.Ok).records
        assertEquals(3, records.size)
        assertEquals(newId, records[0].id)
        assertEquals(midId, records[1].id)
        assertEquals(oldId, records[2].id)
    }

    @Test
    fun `corrupted file returns Partial with corruptedCount`() {
        val goodId = UUID.randomUUID().toString()
        store.save(completedRecord(id = goodId))

        // 写入一个损坏的 JSON 文件（文件名必须是 UUID 格式才会被 list 扫描）。
        val corruptedId = UUID.randomUUID().toString()
        val corruptedFile = File(tempFolder.root, "$corruptedId.json")
        corruptedFile.writeText("{not valid json", Charsets.UTF_8)

        val result = store.list()
        assertTrue(result is HistoryListResult.Partial)
        val partial = result as HistoryListResult.Partial
        assertEquals(1, partial.records.size)
        assertEquals(goodId, partial.records[0].id)
        assertEquals(1, partial.corruptedCount)
    }

    @Test
    fun `get on corrupted file throws SessionHistoryException`() {
        val badId = UUID.randomUUID().toString()
        val corruptedFile = File(tempFolder.root, "$badId.json")
        corruptedFile.writeText("invalid", Charsets.UTF_8)

        // get() 对损坏文件抛出异常，不返回 null。
        val exception = assertThrows(SessionHistoryException::class.java) {
            store.get(badId)
        }
        assertNotNull(exception.message)
    }

    @Test
    fun `delete is idempotent for non-existent record`() {
        assertTrue(store.delete(UUID.randomUUID().toString()))
    }

    @Test
    fun `delete removes existing record`() {
        val id = UUID.randomUUID().toString()
        store.save(completedRecord(id = id))
        assertNotNull(store.get(id))

        assertTrue(store.delete(id))
        assertNull(store.get(id))
    }

    @Test
    fun `deleteAll removes all records`() {
        repeat(3) {
            store.save(completedRecord(id = UUID.randomUUID().toString()))
        }

        val deleted = store.deleteAll()
        assertEquals(3, deleted)

        val result = store.list()
        assertTrue(result is HistoryListResult.Ok)
        assertEquals(0, (result as HistoryListResult.Ok).records.size)
    }

    @Test
    fun `record limit default is 500 and small writes are not evicted`() {
        // 默认上限 MAX_RECORD_COUNT = 500，写入少量记录不会触发 FIFO 删除。
        repeat(10) { index ->
            store.save(completedRecord(id = UUID.randomUUID().toString(), endTimeMs = (index + 1).toLong() * 1000))
        }

        val result = store.list()
        assertTrue(result is HistoryListResult.Ok)
        val records = (result as HistoryListResult.Ok).records
        assertEquals(10, records.size)
    }

    @Test
    fun `failed record has needsAttention true`() {
        val failed = SessionHistoryRecord(
            id = UUID.randomUUID().toString(),
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
        val completed = completedRecord(id = UUID.randomUUID().toString())
        assertFalse(completed.needsAttention)
        assertEquals("已完成", completed.statusLabel)
    }

    @Test
    fun `task must be fixed summary rejects raw task`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            SessionHistoryRecord(
                id = UUID.randomUUID().toString(),
                task = "用户原始任务包含密码",
                targetPackage = "com.example",
                phase = SessionPhase.Completed,
                startTimeMs = 100,
                endTimeMs = 200,
            )
        }
        assertTrue(exception.message!!.contains("fixed summary"))
    }

    @Test
    fun `save rejects non-uuid id`() {
        // 构造函数只验证非空，id 格式验证在 store.save 的 fileFor 中。
        val record = SessionHistoryRecord(
            id = "not-a-uuid",
            task = SessionHistoryRecord.TASK_SUMMARY,
            targetPackage = "com.example",
            phase = SessionPhase.Completed,
            startTimeMs = 100,
            endTimeMs = 200,
        )
        val exception = assertThrows(IllegalArgumentException::class.java) {
            store.save(record)
        }
        assertTrue(exception.message!!.contains("Invalid session history id"))
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
