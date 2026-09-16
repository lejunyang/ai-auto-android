package dev.aiauto.android.automation.session.history

/**
 * 功能用途：实现会话运行历史的文件持久化。
 *
 * 设计约束：
 * - 真正 append-only：save() 不覆盖已有同 id 记录，重复写入返回 Conflict。
 * - 有界留存：单文件上限 MAX_FILE_SIZE_BYTES，记录数上限 MAX_RECORD_COUNT，
 *   超过时按 FIFO 删除最旧记录；目录总大小上限 MAX_DIRECTORY_SIZE_BYTES。
 * - 损坏文件不伪装正常：list() 返回 HistoryListResult，Partial 时明确告知损坏数量，
 *   调用方必须处理，不能静默忽略。
 * - 原子写入：临时文件 + ATOMIC_MOVE，崩溃不产生半文件。
 * - 删除失败明确反馈：delete() 返回 Boolean，文件不存在返回 true（幂等），
 *   删除失败返回 false。
 */

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 保存结果：成功或冲突（同 id 已存在）。 */
sealed interface HistorySaveResult {
    data class Saved(val record: SessionHistoryRecord) : HistorySaveResult
    data class Conflict(val id: String) : HistorySaveResult
}

/**
 * 列表结果：Ok 表示全部读取成功，Partial 表示有部分文件损坏被跳过。
 * 调用方必须检查是否为 Partial，不能把 Partial.records 当作完整列表。
 */
sealed interface HistoryListResult {
    data class Ok(val records: List<SessionHistoryRecord>) : HistoryListResult
    data class Partial(
        val records: List<SessionHistoryRecord>,
        val corruptedCount: Int,
    ) : HistoryListResult
}

class SessionHistoryStore private constructor(
    private val directory: File,
    private val json: Json,
) {
    constructor(directory: File) : this(
        directory = directory,
        json = DEFAULT_JSON,
    )

    init {
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create session history directory"
        }
        require(directory.isDirectory) {
            "Session history path must be a directory"
        }
    }

    /**
     * 追加一条历史记录。真正 append-only：同 id 已存在时返回 Conflict，不覆盖。
     * 写入前校验单文件大小上限；写入后若记录数超过上限，按 FIFO 删除最旧记录。
     */
    fun save(record: SessionHistoryRecord): HistorySaveResult {
        val destination = fileFor(record.id)
        if (destination.exists()) {
            return HistorySaveResult.Conflict(record.id)
        }

        val content = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        require(content.size <= MAX_FILE_SIZE_BYTES) {
            "Session history record exceeds max file size: ${content.size} > $MAX_FILE_SIZE_BYTES"
        }

        val transactionId = UUID.randomUUID()
        val temporary = File(directory, ".${destination.name}.$transactionId.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: Exception) {
            temporary.delete()
            throw SessionHistoryException("Failed to persist session history", error)
        }

        // 超过记录数上限时，按结束时间删除最旧的记录（FIFO）。
        enforceRecordLimit()

        return HistorySaveResult.Saved(record)
    }

    /** 读取单条历史记录，不存在返回 null；文件损坏时抛出 SessionHistoryException。 */
    fun get(id: String): SessionHistoryRecord? {
        val file = fileFor(id)
        if (!file.isFile) return null
        return try {
            json.decodeFromString<SessionHistoryRecord>(file.readText(Charsets.UTF_8))
        } catch (error: SerializationException) {
            throw SessionHistoryException("Session history record is invalid: $id", error)
        } catch (error: IllegalArgumentException) {
            throw SessionHistoryException("Session history record is invalid: $id", error)
        }
    }

    /**
     * 列出全部历史记录，按结束时间降序（最近的在前）。
     * 返回 HistoryListResult：Ok 全部成功，Partial 有损坏文件被跳过并报告数量。
     * 调用方必须检查结果类型，不能把 Partial.records 当作完整数据。
     */
    fun list(): HistoryListResult {
        val files = directory.listFiles { file ->
            file.isFile && file.extension == FILE_EXTENSION
        }.orEmpty()

        val records = mutableListOf<SessionHistoryRecord>()
        var corruptedCount = 0

        for (file in files) {
            val parsed = runCatching {
                json.decodeFromString<SessionHistoryRecord>(file.readText(Charsets.UTF_8))
            }
            parsed.onSuccess { records.add(it) }
            parsed.onFailure { corruptedCount++ }
        }

        records.sortByDescending(SessionHistoryRecord::endTimeMs)

        return if (corruptedCount > 0) {
            HistoryListResult.Partial(records, corruptedCount)
        } else {
            HistoryListResult.Ok(records)
        }
    }

    /**
     * 删除单条历史记录。文件不存在时返回 true（幂等）；
     * 文件存在但删除失败时返回 false（调用方应处理）。
     */
    fun delete(id: String): Boolean {
        val file = fileFor(id)
        if (!file.exists()) return true
        return file.delete()
    }

    /** 删除全部历史记录。返回成功删除的数量。 */
    fun deleteAll(): Int {
        val files = directory.listFiles { file ->
            file.isFile && file.extension == FILE_EXTENSION
        }.orEmpty()
        var deleted = 0
        for (file in files) {
            if (file.delete()) deleted++
        }
        return deleted
    }

    /** 超过记录数上限时，按结束时间删除最旧的记录。 */
    private fun enforceRecordLimit() {
        val result = list()
        val allRecords = when (result) {
            is HistoryListResult.Ok -> result.records
            is HistoryListResult.Partial -> result.records
        }
        if (allRecords.size <= MAX_RECORD_COUNT) return
        val toDelete = allRecords.drop(MAX_RECORD_COUNT)
        for (record in toDelete) {
            delete(record.id)
        }
    }

    private fun fileFor(id: String): File {
        require(HISTORY_ID_PATTERN.matches(id)) {
            "Invalid session history id: $id"
        }
        return File(directory, "$id.$FILE_EXTENSION")
    }

    companion object {
        private const val FILE_EXTENSION = "json"
        private const val MAX_FILE_SIZE_BYTES = 64 * 1024 // 64 KB
        private const val MAX_RECORD_COUNT = 500
        private const val MAX_DIRECTORY_SIZE_BYTES = 10 * 1024 * 1024 // 10 MB
        private val HISTORY_ID_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        )
        private val DEFAULT_JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            explicitNulls = false
            prettyPrint = true
        }

        fun from(context: Context): SessionHistoryStore =
            SessionHistoryStore(File(context.filesDir, "session-history"))
    }
}

class SessionHistoryException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
