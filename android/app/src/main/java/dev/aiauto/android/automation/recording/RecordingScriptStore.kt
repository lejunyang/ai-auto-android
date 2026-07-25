package dev.aiauto.android.automation.recording

/**
 * 功能用途：实现 RecordingScriptStore 对应的语义录制、脚本持久化或确定性回放能力。
 */

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 文件操作边界用于验证原子替换和失败清理不会破坏现有脚本。 */
internal interface RecordingFileOperations {
    fun writeAndSync(
        file: File,
        content: ByteArray,
    )

    fun replaceAtomically(
        source: File,
        destination: File,
    )

    fun delete(file: File): Boolean
}

internal object SystemRecordingFileOperations : RecordingFileOperations {
    override fun writeAndSync(
        file: File,
        content: ByteArray,
    ) {
        FileOutputStream(file).use { output ->
            output.write(content)
            output.fd.sync()
        }
    }

    override fun replaceAtomically(
        source: File,
        destination: File,
    ) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun delete(file: File): Boolean = !file.exists() || file.delete()
}

/** 保存结果显式区分成功与 revision 冲突，冲突时保留编辑副本和磁盘当前版本。 */
sealed interface RecordingSaveResult {
    data class Saved(val script: AutomationScript) : RecordingSaveResult

    data class Conflict(
        val expectedRevision: Long,
        val attempted: AutomationScript,
        val current: AutomationScript,
    ) : RecordingSaveResult
}

class RecordingScriptStore private constructor(
    private val directory: File,
    private val migrator: RecordingScriptMigrator,
    private val json: Json,
    private val fileOperations: RecordingFileOperations,
    private val lockTimeoutMs: Long,
) {
    constructor(
        directory: File,
        migrator: RecordingScriptMigrator = RecordingScriptMigrator(),
        json: Json = DEFAULT_JSON,
    ) : this(
        directory = directory,
        migrator = migrator,
        json = json,
        fileOperations = SystemRecordingFileOperations,
        lockTimeoutMs = DEFAULT_LOCK_TIMEOUT_MS,
    )

    internal constructor(
        directory: File,
        fileOperations: RecordingFileOperations,
        lockTimeoutMs: Long = DEFAULT_LOCK_TIMEOUT_MS,
    ) : this(
        directory = directory,
        migrator = RecordingScriptMigrator(),
        json = DEFAULT_JSON,
        fileOperations = fileOperations,
        lockTimeoutMs = lockTimeoutMs,
    )

    private val directoryLock = lockFor(directory)

    init {
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create the recording directory"
        }
        require(directory.isDirectory) {
            "The recording path must be a directory"
        }
        require(lockTimeoutMs > 0) {
            "The recording lock timeout must be positive"
        }
    }

    fun save(
        script: AutomationScript,
        expectedRevision: Long? = null,
    ): RecordingSaveResult = synchronized(directoryLock) {
        validate(script)
        val destination = fileFor(script.id)
        withScriptLock(destination) {
            val current = load(destination, rewriteMigration = false)
            val comparedRevision = expectedRevision ?: script.revision
            if (current != null && current.revision != comparedRevision) {
                return@withScriptLock RecordingSaveResult.Conflict(
                    expectedRevision = comparedRevision,
                    attempted = script,
                    current = current,
                )
            }
            require(current != null || expectedRevision == null || expectedRevision == 0L) {
                "A non-zero expected revision cannot create a recording script"
            }
            val persisted = if (current == null) {
                script
            } else {
                script.copy(
                    revision = current.revision + 1,
                    updatedAt = maxOf(Instant.now(), Instant.parse(current.updatedAt)).toString(),
                )
            }
            validate(persisted)
            writeAtomically(destination, json.encodeToString(persisted))
            RecordingSaveResult.Saved(persisted)
        }
    }

    fun get(id: String): AutomationScript? = synchronized(directoryLock) {
        val file = fileFor(id)
        withScriptLock(file) {
            val script = load(file, rewriteMigration = true) ?: return@withScriptLock null
            require(script.id == id) {
                "The script id does not match its storage key"
            }
            script
        }
    }

    fun list(): List<AutomationScriptSummary> = synchronized(directoryLock) {
        directory.listFiles { file -> file.isFile && file.extension == FILE_EXTENSION }
            .orEmpty()
            .mapNotNull { file -> runCatching { get(file.nameWithoutExtension) }.getOrNull() }
            .map { script ->
                AutomationScriptSummary(
                    id = script.id,
                    name = script.name,
                    targetPackages = script.targetPackages,
                    createdAt = script.createdAt,
                    stepCount = script.steps.size,
                    requirements = script.requirements,
                )
            }
            .sortedByDescending(AutomationScriptSummary::createdAt)
    }

    fun delete(id: String): Boolean = synchronized(directoryLock) {
        val file = fileFor(id)
        withScriptLock(file) {
            val deleted = fileOperations.delete(file)
            if (deleted) {
                fileOperations.delete(backupFor(file))
            }
            deleted
        }
    }

    /** 严格导入只返回已迁移模型，不在校验完成前触碰正式脚本文件。 */
    fun importScript(content: String): AutomationScript =
        try {
            val migrated = migrator.migrate(content)
            decode(migrated.content).also { script ->
                validate(script, strictCommands = true)
            }
        } catch (error: RecordingStorageException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw RecordingStorageException("The recording script is invalid", error)
        }

    /**
     * 默认导出删除敏感变量默认值、截图字节和设备本地路径，但保留运行时 secret 引用。
     */
    fun exportScript(
        script: AutomationScript,
        includeSensitiveArtifacts: Boolean = false,
    ): String {
        validate(script, strictCommands = true)
        val exported = if (includeSensitiveArtifacts) {
            script
        } else {
            script.copy(
                variables = script.variables.map { variable ->
                    if (variable.sensitive || variable.type == "secret") {
                        variable.copy(defaultValue = null)
                    } else {
                        variable.copy(
                            defaultValue = variable.defaultValue?.let {
                                sanitizeJsonElement("default", it)
                            },
                        )
                    }
                },
                steps = script.steps.map { step ->
                    step.copy(
                        action = step.action.copy(
                            params = sanitizeJsonObject(step.action.params),
                        ),
                        visualTarget = step.visualTarget?.copy(
                            screenshotBase64 = null,
                            deviceLocalPath = null,
                        ),
                        waitBefore = sanitizePredicate(step.waitBefore),
                        waitAfter = sanitizePredicate(step.waitAfter),
                    )
                },
            )
        }
        validate(exported, strictCommands = true)
        return json.encodeToString(exported)
    }

    private fun sanitizePredicate(predicate: RecordedPredicate?): RecordedPredicate? =
        predicate?.copy(
            target = predicate.target?.let(::sanitizeJsonObject),
            expected = predicate.expected?.let { sanitizeJsonElement("expected", it) },
        )

    private fun sanitizeJsonObject(value: JsonObject): JsonObject = buildJsonObject {
        value.forEach { (key, element) ->
            sanitizeJsonElement(key, element)?.let { put(key, it) }
        }
    }

    private fun sanitizeJsonElement(
        key: String,
        value: JsonElement,
    ): JsonElement? {
        if (normalizeExportKey(key) in SENSITIVE_EXPORT_KEYS) {
            return null
        }
        return when (value) {
            is JsonObject -> sanitizeJsonObject(value)
            is JsonArray -> JsonArray(
                value.mapNotNull { element -> sanitizeJsonElement(key, element) },
            )

            is JsonPrimitive -> if (
                value.isString &&
                value.contentOrNull?.let(::isDeviceLocalAbsolutePath) == true
            ) {
                null
            } else {
                value
            }
        }
    }

    private fun load(
        file: File,
        rewriteMigration: Boolean,
    ): AutomationScript? {
        if (!file.isFile) {
            return null
        }
        val original = file.readText()
        val migrated = migrator.migrate(original)
        val script = decode(migrated.content)
        validate(script)
        if (migrated.changed && rewriteMigration) {
            writeAtomically(file, json.encodeToString(script))
        }
        return script
    }

    private fun decode(content: String): AutomationScript =
        try {
            json.decodeFromString(content)
        } catch (error: SerializationException) {
            throw RecordingStorageException("The recording script is invalid", error)
        } catch (error: IllegalArgumentException) {
            throw RecordingStorageException("The recording script is invalid", error)
        }

    private fun fileFor(id: String): File {
        require(SCRIPT_ID_PATTERN.matches(id)) {
            "The script id is invalid"
        }
        return File(directory, "$id.$FILE_EXTENSION")
    }

    private fun writeAtomically(destination: File, content: String) {
        // 备份先于正式替换落盘；替换失败时从备份回滚，且固定备份保留供人工恢复。
        val transactionId = UUID.randomUUID()
        val temporary = File(directory, ".${destination.name}.$transactionId.tmp")
        val backupTemporary = File(directory, ".${destination.name}.$transactionId.bak.tmp")
        val rollbackTemporary = File(directory, ".${destination.name}.$transactionId.rollback.tmp")
        val backup = backupFor(destination)
        var hasBackup = false
        try {
            fileOperations.writeAndSync(temporary, content.toByteArray(Charsets.UTF_8))
            if (destination.isFile) {
                fileOperations.writeAndSync(backupTemporary, destination.readBytes())
                fileOperations.replaceAtomically(backupTemporary, backup)
                hasBackup = true
            }
            fileOperations.replaceAtomically(temporary, destination)
        } catch (error: Exception) {
            if (hasBackup) {
                try {
                    fileOperations.writeAndSync(rollbackTemporary, backup.readBytes())
                    fileOperations.replaceAtomically(rollbackTemporary, destination)
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
            }
            try {
                cleanupTemporaryFiles(temporary, backupTemporary, rollbackTemporary)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw RecordingStorageException("Unable to persist the recording script", error)
        } finally {
            try {
                cleanupTemporaryFiles(temporary, backupTemporary, rollbackTemporary)
            } catch (_: Exception) {
                // 主操作成功后清理失败不回报伪失败；下次目录维护仍可识别这些事务临时文件。
            }
        }
    }

    private fun cleanupTemporaryFiles(vararg files: File) {
        var failure: Exception? = null
        files.forEach { file ->
            try {
                check(fileOperations.delete(file)) {
                    "Unable to remove the temporary recording file"
                }
            } catch (error: Exception) {
                if (failure == null) {
                    failure = error
                } else {
                    failure?.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun backupFor(destination: File): File = File(directory, "${destination.name}.bak")

    /**
     * JVM 互斥只用于避免同进程重叠锁异常；OS 文件锁覆盖 revision 读取到原子替换的事务。
     */
    private fun <T> withScriptLock(
        destination: File,
        action: () -> T,
    ): T {
        val lockFile = File(directory, ".${destination.name}.lock")
        val access = try {
            RandomAccessFile(lockFile, "rw")
        } catch (error: Exception) {
            throw RecordingStorageException(LOCK_FAILURE_MESSAGE, error)
        }
        try {
            access.channel.use { channel ->
                val lock = try {
                    acquireLock(channel = channel, lockFile = lockFile)
                } catch (error: RecordingStorageException) {
                    throw error
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw RecordingStorageException(LOCK_FAILURE_MESSAGE, error)
                } catch (error: Exception) {
                    throw RecordingStorageException(LOCK_FAILURE_MESSAGE, error)
                }
                lock.use {
                    return action()
                }
            }
        } finally {
            access.close()
        }
    }

    private fun acquireLock(
        channel: java.nio.channels.FileChannel,
        lockFile: File,
    ): FileLock {
        val deadline = System.nanoTime() + lockTimeoutMs * NANOS_PER_MILLISECOND
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock != null) {
                return lock
            }
            if (System.nanoTime() >= deadline) {
                throw RecordingStorageException(
                    "$LOCK_FAILURE_MESSAGE: ${lockFile.name}",
                )
            }
            Thread.sleep(LOCK_RETRY_INTERVAL_MS)
        }
    }

    private fun validate(script: AutomationScript) {
        validate(script, strictCommands = false)
    }

    private fun validate(
        script: AutomationScript,
        strictCommands: Boolean,
    ) {
        require(SCRIPT_ID_PATTERN.matches(script.id))
        require(script.schemaVersion == RECORDING_SCHEMA_VERSION)
        require(script.revision in 1..MAX_SAFE_JSON_INTEGER)
        require(script.name.isNotBlank() && script.name.length <= 128)
        require(script.targetPackages.isNotEmpty() && script.targetPackages.size <= 32)
        require(script.targetPackages.distinct().size == script.targetPackages.size)
        require(script.targetPackages.all(PACKAGE_NAME_PATTERN::matches))
        require(runCatching { Instant.parse(script.createdAt) }.isSuccess)
        require(runCatching { Instant.parse(script.updatedAt) }.isSuccess)
        require(Instant.parse(script.updatedAt) >= Instant.parse(script.createdAt))
        require(script.requirements.minApiLevel in 30..1_000)
        require(script.requirements.capabilities.size <= 64)
        require(
            script.requirements.capabilities.distinct().size ==
                script.requirements.capabilities.size,
        )
        require(
            script.requirements.capabilities.all { capability ->
                capability.isNotBlank() && capability.length <= 128
            },
        )
        validateEnvironment(script.environment)
        require(script.steps.isNotEmpty() && script.steps.size <= 10_000)
        require(script.steps.distinctBy(RecordedStep::id).size == script.steps.size)
        require(script.variables.size <= 128)
        require(script.variables.distinctBy(ScriptVariable::name).size == script.variables.size)
        script.variables.forEach(::validateVariable)
        script.steps.forEach { step ->
            require(SCRIPT_ID_PATTERN.matches(step.id))
            require(step.recordedAtMs >= 0)
            require(step.notes == null || step.notes.length <= 4_096)
            validatePredicate(step.waitBefore, strictCommands)
            validatePredicate(step.waitAfter, strictCommands)
            validateVisualTarget(step.visualTarget)
            validateAction(step.action, strictCommands)
            if (step.action.type == "ui.setText") {
                // 文本动作必须在明文和 secret 引用之间二选一，禁止两种来源混存。
                val hasText = step.action.params.containsKey("text")
                val hasSecret = step.action.params.containsKey("secretRef")
                require(hasText.xor(hasSecret)) {
                    "Text actions must contain exactly one text source"
                }
            }
        }
    }

    private fun validateEnvironment(environment: ScriptEnvironment?) {
        environment ?: return
        require(environment.apiLevel == null || environment.apiLevel >= 1)
        require(environment.logicalWidth == null || environment.logicalWidth >= 1)
        require(environment.logicalHeight == null || environment.logicalHeight >= 1)
        require(environment.densityDpi == null || environment.densityDpi >= 1)
        require(environment.rotation == null || environment.rotation in setOf(0, 90, 180, 270))
        require(environment.locale == null || environment.locale.length in 2..64)
        require(environment.fontScale == null || environment.fontScale in 0.5f..3.0f)
        require(environment.appVersion == null || environment.appVersion.length in 1..128)
    }

    private fun validateVariable(variable: ScriptVariable) {
        require(VARIABLE_NAME_PATTERN.matches(variable.name))
        require(variable.type in VARIABLE_TYPES)
        require(variable.scope in VARIABLE_SCOPES)
    }

    private fun validateVisualTarget(target: VisualTarget?) {
        target ?: return
        require(target.normalizedPoint != null || target.normalizedBounds != null)
        target.normalizedPoint?.let {
            require(it.x in 0.0..1.0 && it.y in 0.0..1.0)
        }
        target.normalizedBounds?.let {
            require(it.left in 0.0..1.0 && it.top in 0.0..1.0)
            require(it.right in 0.0..1.0 && it.bottom in 0.0..1.0)
            require(it.right > it.left && it.bottom > it.top)
        }
        require(target.confidence in 0.0..1.0)
        require(target.observationId == null || target.observationId.length in 1..128)
        require(
            target.imageSha256 == null ||
                target.imageSha256.matches(Regex("^[0-9a-fA-F]{64}$")),
        )
        require(target.screenshotBase64 == null || target.screenshotBase64.length <= 16_777_216)
        require(target.deviceLocalPath == null || isDeviceLocalAbsolutePath(target.deviceLocalPath))
    }

    private fun validateAction(
        action: RecordedAction,
        strict: Boolean,
    ) {
        val allowed = ACTION_PARAMETER_KEYS[action.type]
            ?: throw IllegalArgumentException("Unsupported recorded action: ${action.type}")
        require(action.params.keys.all(allowed::contains)) {
            "The recorded action contains unknown parameters"
        }
        if (!strict) {
            action.params["target"]?.let { validateTarget(it.jsonObject, strict = false) }
            return
        }
        when (action.type) {
            "app.launch", "app.stop" -> {
                require("packageName" in action.params)
                require(
                    action.params.getValue("packageName").jsonPrimitive.content
                        .matches(PACKAGE_NAME_PATTERN),
                )
                action.params["activity"]?.let {
                    require(it.jsonPrimitive.content.length in 1..512)
                }
            }

            "ui.click", "ui.longClick" -> {
                require("target" in action.params)
                validateTarget(action.params.getValue("target").jsonObject, strict = true)
                action.params["durationMs"]?.let {
                    require(it.jsonPrimitive.intOrNull?.let { value -> value in 300..10_000 } == true)
                }
            }

            "ui.tap" -> validateScreenPoint(action.params)
            "ui.swipe" -> {
                require(ACTION_PARAMETER_KEYS.getValue("ui.swipe").all(action.params::containsKey))
                validateScreenPoint(action.params.getValue("start").jsonObject)
                validateScreenPoint(action.params.getValue("end").jsonObject)
                require(
                    action.params.getValue("durationMs").jsonPrimitive.intOrNull
                        ?.let { it in 1..60_000 } == true,
                )
            }

            "ui.setText" -> {
                require("target" in action.params)
                validateTarget(action.params.getValue("target").jsonObject, strict = true)
            }

            "ui.scroll" -> {
                require(
                    action.params["direction"]?.jsonPrimitive?.contentOrNull in SCROLL_DIRECTIONS,
                )
                action.params["target"]?.let { validateTarget(it.jsonObject, strict = true) }
                action.params["amount"]?.let {
                    require(
                        it.jsonPrimitive.doubleOrNull
                            ?.let { value -> value > 0.0 && value <= 1.0 } == true,
                    )
                }
            }

            "ui.wait", "ui.assert" -> validatePredicateObject(action.params)
            "ui.back", "ui.home", "ui.recents" -> require(action.params.isEmpty())
            "task.finish" -> action.params["summary"]?.let {
                require(it.jsonPrimitive.content.length <= 4_096)
            }
        }
    }

    private fun validatePredicate(
        predicate: RecordedPredicate?,
        strict: Boolean,
    ) {
        predicate ?: return
        require(predicate.kind in PREDICATE_KINDS)
        require(predicate.operator in PREDICATE_OPERATORS)
        require(predicate.timeoutMs in 1..300_000)
        require(predicate.stableDurationMs == null || predicate.stableDurationMs in 100..30_000)
        predicate.target?.let { validateTarget(it, strict) }
    }

    private fun validatePredicateObject(predicate: JsonObject) {
        require(predicate.keys.all(PREDICATE_KEYS::contains))
        require("kind" in predicate && "operator" in predicate)
        require(predicate.getValue("kind").jsonPrimitive.content in PREDICATE_KINDS)
        require(predicate.getValue("operator").jsonPrimitive.content in PREDICATE_OPERATORS)
        predicate["stableDurationMs"]?.let {
            require(
                it.jsonPrimitive.intOrNull?.let { value -> value in 100..30_000 } == true,
            )
        }
        predicate["timeoutMs"]?.let {
            require(
                it.jsonPrimitive.intOrNull?.let { value -> value in 1..300_000 } == true,
            )
        }
        predicate["target"]?.let { validateTarget(it.jsonObject, strict = true) }
    }

    private fun validateTarget(
        target: JsonObject,
        strict: Boolean,
    ) {
        require(target.keys.all(TARGET_KEYS::contains))
        if (strict) {
            require(
                "selectorCandidates" in target ||
                    ("recordedBounds" in target && "relativePoint" in target) ||
                    "normalizedScreenPoint" in target,
            )
        }
        target["packageName"]?.jsonPrimitive?.contentOrNull?.let {
            require(PACKAGE_NAME_PATTERN.matches(it))
        }
        target["selectorCandidates"]?.jsonArray?.forEach { candidate ->
            val value = candidate.jsonObject
            require(value.keys.all(SELECTOR_KEYS::contains))
            require(SELECTOR_REQUIRED_KEYS.all(value::containsKey))
            require(value.getValue("strategy").jsonPrimitive.content in SELECTOR_STRATEGIES)
            require(value.getValue("value").jsonPrimitive.content.length in 1..1_024)
            require(
                value.getValue("weight").jsonPrimitive.doubleOrNull
                    ?.let { it in 0.0..1.0 } == true,
            )
        }
        target["selectorCandidates"]?.jsonArray?.let {
            require(it.size in 1..16)
        }
        target["fingerprint"]?.jsonObject?.let {
            require(it.size in 1..32)
        }
        target["recordedBounds"]?.jsonObject?.let { bounds ->
            require(bounds.keys == BOUNDS_KEYS)
            val left = bounds.getValue("left").jsonPrimitive.intOrNull
            val top = bounds.getValue("top").jsonPrimitive.intOrNull
            val right = bounds.getValue("right").jsonPrimitive.intOrNull
            val bottom = bounds.getValue("bottom").jsonPrimitive.intOrNull
            require(left != null && top != null && right != null && bottom != null)
            require(left >= 0 && top >= 0 && right > left && bottom > top)
        }
        target["relativePoint"]?.jsonObject?.let(::validateNormalizedPoint)
        target["normalizedScreenPoint"]?.jsonObject?.let(::validateNormalizedPoint)
    }

    private fun validateScreenPoint(point: JsonObject) {
        require(point.keys == POINT_KEYS)
        require(point["x"]?.jsonPrimitive?.intOrNull?.let { it >= 0 } == true)
        require(point["y"]?.jsonPrimitive?.intOrNull?.let { it >= 0 } == true)
    }

    private fun validateNormalizedPoint(point: JsonObject) {
        require(point.keys == POINT_KEYS)
        require(point["x"]?.jsonPrimitive?.doubleOrNull?.let { it in 0.0..1.0 } == true)
        require(point["y"]?.jsonPrimitive?.doubleOrNull?.let { it in 0.0..1.0 } == true)
    }

    private fun isDeviceLocalAbsolutePath(path: String): Boolean =
        path.startsWith("/") || path.startsWith("file:///")

    private fun normalizeExportKey(key: String): String =
        key.filter(Char::isLetterOrDigit).lowercase()

    companion object {
        private const val FILE_EXTENSION = "json"
        private const val DEFAULT_LOCK_TIMEOUT_MS = 5_000L
        private const val LOCK_RETRY_INTERVAL_MS = 10L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val LOCK_FAILURE_MESSAGE = "Unable to acquire the recording script lock"
        private const val MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L
        private val SCRIPT_ID_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        )
        private val PACKAGE_NAME_PATTERN =
            Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val VARIABLE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9._-]*$")
        private val VARIABLE_TYPES = setOf("string", "integer", "boolean", "json", "secret")
        private val VARIABLE_SCOPES = setOf("script", "run", "step")
        private val PREDICATE_KINDS = setOf("node", "text", "package", "window", "uiStable")
        private val PREDICATE_OPERATORS =
            setOf("exists", "notExists", "equals", "contains", "matches", "stable")
        private val SCROLL_DIRECTIONS = setOf("up", "down", "left", "right", "forward", "backward")
        private val POINT_KEYS = setOf("x", "y")
        private val BOUNDS_KEYS = setOf("left", "top", "right", "bottom")
        private val TARGET_KEYS = setOf(
            "packageName",
            "selectorCandidates",
            "fingerprint",
            "recordedBounds",
            "relativePoint",
            "normalizedScreenPoint",
        )
        private val SELECTOR_KEYS = setOf("strategy", "value", "weight", "required")
        private val SELECTOR_REQUIRED_KEYS = setOf("strategy", "value", "weight")
        private val SELECTOR_STRATEGIES =
            setOf("resourceId", "contentDescription", "text", "role", "ancestor", "fingerprint")
        private val PREDICATE_KEYS =
            setOf("kind", "target", "operator", "expected", "stableDurationMs", "timeoutMs")
        private val ACTION_PARAMETER_KEYS = mapOf(
            "app.launch" to setOf("packageName", "activity"),
            "app.stop" to setOf("packageName"),
            "ui.click" to setOf("target"),
            "ui.longClick" to setOf("target", "durationMs"),
            "ui.tap" to POINT_KEYS,
            "ui.swipe" to setOf("start", "end", "durationMs"),
            "ui.setText" to setOf("target", "text", "secretRef"),
            "ui.scroll" to setOf("target", "direction", "amount"),
            "ui.back" to emptySet(),
            "ui.home" to emptySet(),
            "ui.recents" to emptySet(),
            "ui.wait" to PREDICATE_KEYS,
            "ui.assert" to PREDICATE_KEYS,
            "task.finish" to setOf("summary"),
        )
        private val SENSITIVE_EXPORT_KEYS = setOf(
            "accesstoken",
            "apikey",
            "authorization",
            "clientsecret",
            "cookie",
            "credential",
            "devicelocalpath",
            "localpath",
            "password",
            "refreshtoken",
            "secret",
            "secretvalue",
            "sessiontoken",
            "screenshot",
            "screenshotbase64",
            "screenshotbytes",
            "token",
        )
        private val DIRECTORY_LOCKS = ConcurrentHashMap<String, Any>()
        private val DEFAULT_JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            explicitNulls = false
            prettyPrint = true
        }

        private fun lockFor(directory: File): Any =
            DIRECTORY_LOCKS.computeIfAbsent(
                directory.absoluteFile.toPath().normalize().toString(),
            ) { Any() }

        fun from(context: Context): RecordingScriptStore =
            RecordingScriptStore(File(context.filesDir, "recordings"))
    }
}

class RecordingScriptMigrator(
    private val json: Json = Json {
        explicitNulls = false
    },
) {
    data class Result(
        val content: String,
        val changed: Boolean,
    )

    fun migrate(content: String): Result {
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (error: Exception) {
            throw RecordingStorageException("The recording script is not valid JSON", error)
        }
        val version = root["schemaVersion"]?.jsonPrimitive?.contentOrNull
        return when (version) {
            RECORDING_SCHEMA_VERSION -> Result(content = content, changed = false)
            RECORDING_LEGACY_SCHEMA_VERSION -> Result(
                content = migrateVersion1(root).toString(),
                changed = true,
            )

            PRE_VERSIONED_SCHEMA_VERSION, null -> Result(
                content = migrateVersion1(migratePreVersioned(root)).toString(),
                changed = true,
            )

            else -> throw RecordingStorageException(
                "Unsupported recording schema version: $version",
            )
        }
    }

    private fun migrateVersion1(root: JsonObject): JsonObject = buildJsonObject {
        root.forEach(::put)
        put("schemaVersion", JsonPrimitive(RECORDING_SCHEMA_VERSION))
        put("revision", root["revision"] ?: JsonPrimitive(1))
        put("updatedAt", root["updatedAt"] ?: root["createdAt"] ?: JsonPrimitive(Instant.EPOCH.toString()))
        put("provenance", root["provenance"] ?: JsonPrimitive("semantic"))
        put("steps", migrateVersion1Steps(root["steps"]))
    }

    private fun migrateVersion1Steps(element: JsonElement?): JsonArray {
        val steps = element as? JsonArray ?: JsonArray(emptyList())
        return JsonArray(
            steps.map { raw ->
                val step = raw as? JsonObject ?: return@map raw
                buildJsonObject {
                    step.forEach(::put)
                    put("provenance", step["provenance"] ?: inferStepProvenance(step))
                    put("enabled", step["enabled"] ?: JsonPrimitive(true))
                }
            },
        )
    }

    private fun inferStepProvenance(step: JsonObject): JsonPrimitive {
        val actionType = step["action"]
            ?.let { it as? JsonObject }
            ?.get("type")
            ?.jsonPrimitive
            ?.contentOrNull
        return JsonPrimitive(
            if (actionType in COORDINATE_ACTIONS) "coordinate" else "semantic",
        )
    }

    private fun migratePreVersioned(root: JsonObject): JsonObject = buildJsonObject {
        root.forEach { (key, value) ->
            if (key !in PRE_VERSIONED_KEYS) {
                put(key, value)
            }
        }
        put("schemaVersion", JsonPrimitive(RECORDING_LEGACY_SCHEMA_VERSION))
        put("name", root["name"] ?: root["title"] ?: JsonPrimitive("Imported recording"))
        put(
            "targetPackages",
            root["targetPackages"] ?: root["packages"] ?: JsonArray(emptyList()),
        )
        put(
            "requirements",
            root["requirements"] ?: buildJsonObject {
                put("minApiLevel", JsonPrimitive(30))
                put(
                    "capabilities",
                    buildJsonArray {
                        add(JsonPrimitive("accessibility.snapshot"))
                        add(JsonPrimitive("accessibility.action"))
                    },
                )
            },
        )
        put("variables", root["variables"] ?: JsonArray(emptyList()))
        put("steps", migratePreVersionedSteps(root["steps"]))
    }

    private fun migratePreVersionedSteps(element: JsonElement?): JsonArray {
        val steps = element as? JsonArray ?: JsonArray(emptyList())
        return JsonArray(
            steps.map { raw ->
                val step = raw as? JsonObject ?: return@map raw
                buildJsonObject {
                    step.forEach(::put)
                    if ("retry" !in step) {
                        put(
                            "retry",
                            buildJsonObject {
                                put("maxAttempts", JsonPrimitive(2))
                                put("backoffMs", JsonPrimitive(250))
                            },
                        )
                    }
                    if ("failurePolicy" !in step) {
                        put("failurePolicy", JsonPrimitive("stop"))
                    }
                }
            },
        )
    }

    private companion object {
        const val PRE_VERSIONED_SCHEMA_VERSION = "0.9"
        val PRE_VERSIONED_KEYS =
            setOf("schemaVersion", "name", "title", "targetPackages", "packages")
        val COORDINATE_ACTIONS = setOf("ui.tap", "ui.swipe")
    }
}

class RecordingStorageException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
