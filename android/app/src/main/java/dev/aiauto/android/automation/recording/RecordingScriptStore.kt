package dev.aiauto.android.automation.recording

// 功能用途：实现 RecordingScriptStore 对应的语义录制、脚本持久化或确定性回放能力。

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RecordingScriptStore(
    private val directory: File,
    private val migrator: RecordingScriptMigrator = RecordingScriptMigrator(),
    private val json: Json = DEFAULT_JSON,
) {
    init {
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create the recording directory"
        }
        require(directory.isDirectory) {
            "The recording path must be a directory"
        }
    }

    @Synchronized
    fun save(script: AutomationScript) {
        validate(script)
        val encoded = json.encodeToString(script)
        writeAtomically(fileFor(script.id), encoded)
    }

    @Synchronized
    fun get(id: String): AutomationScript? {
        val file = fileFor(id)
        if (!file.isFile) {
            return null
        }
        val original = file.readText()
        val migrated = migrator.migrate(original)
        val script = decode(migrated.content)
        validate(script)
        require(script.id == id) {
            "The script id does not match its storage key"
        }
        if (migrated.changed) {
            writeAtomically(file, json.encodeToString(script))
        }
        return script
    }

    @Synchronized
    fun list(): List<AutomationScriptSummary> =
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

    @Synchronized
    fun delete(id: String): Boolean {
        val file = fileFor(id)
        return !file.exists() || file.delete()
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
        // 先落临时文件并同步到磁盘，再替换正式脚本，避免进程中断留下半写 JSON。
        val temporary = File(directory, ".${destination.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                check(temporary.delete()) {
                    "Unable to remove the temporary recording file"
                }
            }
        } catch (error: Exception) {
            temporary.delete()
            throw RecordingStorageException("Unable to persist the recording script", error)
        }
    }

    private fun validate(script: AutomationScript) {
        require(SCRIPT_ID_PATTERN.matches(script.id))
        require(script.schemaVersion == RECORDING_SCHEMA_VERSION)
        require(script.name.isNotBlank() && script.name.length <= 128)
        require(script.targetPackages.isNotEmpty() && script.targetPackages.size <= 32)
        require(script.targetPackages.distinct().size == script.targetPackages.size)
        require(script.targetPackages.all(PACKAGE_NAME_PATTERN::matches))
        require(runCatching { Instant.parse(script.createdAt) }.isSuccess)
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
        require(script.steps.isNotEmpty() && script.steps.size <= 10_000)
        require(script.variables.distinctBy(ScriptVariable::name).size == script.variables.size)
        script.steps.forEach { step ->
            require(SCRIPT_ID_PATTERN.matches(step.id))
            require(step.recordedAtMs >= 0)
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

    companion object {
        private const val FILE_EXTENSION = "json"
        private val SCRIPT_ID_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        )
        private val PACKAGE_NAME_PATTERN =
            Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val DEFAULT_JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
            prettyPrint = true
        }

        fun from(context: Context): RecordingScriptStore =
            RecordingScriptStore(File(context.filesDir, "recordings"))
    }
}

class RecordingScriptMigrator(
    private val json: Json = Json {
        ignoreUnknownKeys = true
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
            LEGACY_SCHEMA_VERSION, null -> Result(
                content = migrateLegacy(root).toString(),
                changed = true,
            )

            else -> throw RecordingStorageException(
                "Unsupported recording schema version: $version",
            )
        }
    }

    private fun migrateLegacy(root: JsonObject): JsonObject = buildJsonObject {
        root.forEach { (key, value) ->
            if (key !in LEGACY_KEYS) {
                put(key, value)
            }
        }
        put("schemaVersion", JsonPrimitive(RECORDING_SCHEMA_VERSION))
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
        put("steps", migrateLegacySteps(root["steps"]))
    }

    private fun migrateLegacySteps(element: JsonElement?): JsonArray {
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
        const val LEGACY_SCHEMA_VERSION = "0.9"
        val LEGACY_KEYS = setOf("schemaVersion", "name", "title", "targetPackages", "packages")
    }
}

class RecordingStorageException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
