package dev.aiauto.android.automation.recording

/**
 * 测试用途：验证 RecordingScriptStore 的功能契约、失败语义及自动化安全边界。
 */

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingScriptStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `save list get and delete preserve a complete script`() {
        val directory = temporaryFolder.newFolder("recordings")
        val store = RecordingScriptStore(directory)
        val script = script(SCRIPT_ID, "First")

        val result = store.save(script)

        assertEquals(RecordingSaveResult.Saved(script), result)
        assertEquals(script, store.get(script.id))
        assertEquals(
            listOf(
                AutomationScriptSummary(
                    id = script.id,
                    name = script.name,
                    targetPackages = script.targetPackages,
                    createdAt = script.createdAt,
                    stepCount = 1,
                    requirements = script.requirements,
                ),
            ),
            store.list(),
        )
        assertTrue(store.delete(script.id))
        assertNull(store.get(script.id))
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `version 1_0 is migrated without losing existing fields`() {
        val directory = temporaryFolder.newFolder("version-1-0")
        val file = File(directory, "$FIXTURE_SCRIPT_ID.json")
        val original = fixture("automation-script-v1.0.json")
        file.writeText(original)
        val store = RecordingScriptStore(directory)

        val migrated = store.get(FIXTURE_SCRIPT_ID)

        assertNotNull(migrated)
        assertEquals(RECORDING_SCHEMA_VERSION, migrated?.schemaVersion)
        assertEquals(1L, migrated?.revision)
        assertEquals(migrated?.createdAt, migrated?.updatedAt)
        assertEquals(RecordingProvenance.SEMANTIC, migrated?.provenance)
        assertEquals("Save a note", migrated?.name)
        assertEquals(JsonPrimitive("fixture note"), migrated?.variables?.single()?.defaultValue)
        assertEquals(2, migrated?.steps?.single()?.retry?.maxAttempts)
        assertEquals(200L, migrated?.steps?.single()?.retry?.backoffMs)
        assertEquals(RecordingProvenance.COORDINATE, migrated?.steps?.single()?.provenance)
        assertEquals(true, migrated?.steps?.single()?.enabled)
        assertTrue(file.readText().contains("\"schemaVersion\": \"1.1\""))
        assertFalse(file.readText().contains("\"schemaVersion\": \"1.0\""))
    }

    @Test
    fun `version 1_1 protocol fixture round trips through Kotlin`() {
        val store = RecordingScriptStore(temporaryFolder.newFolder("round-trip"))
        val original = fixture("automation-script-v1.1.json")

        val script = store.importScript(original)
        val encoded = store.exportScript(script, includeSensitiveArtifacts = true)

        assertEquals(
            Json.parseToJsonElement(original),
            Json.parseToJsonElement(encoded),
        )
    }

    @Test
    fun `unknown schema major is rejected without rewriting the source`() {
        val directory = temporaryFolder.newFolder("unknown")
        val file = File(directory, "$SCRIPT_ID.json")
        val original = """
            {"id":"$SCRIPT_ID","schemaVersion":"2.0"}
        """.trimIndent()
        file.writeText(original)
        val store = RecordingScriptStore(directory)

        assertThrows(RecordingStorageException::class.java) {
            store.get(SCRIPT_ID)
        }
        assertEquals(original, file.readText())
    }

    @Test
    fun `corrupt JSON is rejected without rewriting the source`() {
        val directory = temporaryFolder.newFolder("corrupt")
        val file = File(directory, "$SCRIPT_ID.json")
        val original = """{"id":"$SCRIPT_ID","schemaVersion":"1.0""""
        file.writeText(original)
        val store = RecordingScriptStore(directory)

        assertThrows(RecordingStorageException::class.java) {
            store.get(SCRIPT_ID)
        }

        assertEquals(original, file.readText())
    }

    @Test
    fun `strict import rejects unknown fields`() {
        val store = RecordingScriptStore(temporaryFolder.newFolder("strict-import"))
        val original = fixture("automation-script-v1.1.json")
        val withUnknownField = original.replace(
            "\"revision\": 4,",
            "\"revision\": 4,\n  \"unknownField\": true,",
        )

        assertThrows(RecordingStorageException::class.java) {
            store.importScript(withUnknownField)
        }
    }

    @Test
    fun `strict import rejects unknown nested action fields`() {
        val store = RecordingScriptStore(temporaryFolder.newFolder("strict-nested-import"))
        val original = fixture("automation-script-v1.1.json")
        val withUnknownActionField = original.replace(
            "\"x\": 540,\n          \"y\": 1800",
            "\"x\": 540,\n          \"y\": 1800,\n          \"screenshot\": \"unsafe\"",
        )

        assertThrows(RecordingStorageException::class.java) {
            store.importScript(withUnknownActionField)
        }
    }

    @Test
    fun `stale revision returns both versions without overwriting current data`() {
        val directory = temporaryFolder.newFolder("conflict")
        val store = RecordingScriptStore(directory)
        val original = script(SCRIPT_ID, "Original")
        store.save(original)
        val updated = (store.save(
            script = original.copy(name = "Current"),
            expectedRevision = original.revision,
        ) as RecordingSaveResult.Saved).script

        val result = store.save(
            script = original.copy(name = "Stale"),
            expectedRevision = original.revision,
        )

        assertEquals(
            RecordingSaveResult.Conflict(
                expectedRevision = original.revision,
                attempted = original.copy(name = "Stale"),
                current = updated,
            ),
            result,
        )
        assertEquals(updated, store.get(SCRIPT_ID))
        assertEquals(2, updated.revision)
    }

    @Test
    fun `concurrent stores allow exactly one update for the same revision`() {
        val directory = temporaryFolder.newFolder("concurrent")
        val firstStore = RecordingScriptStore(directory)
        val secondStore = RecordingScriptStore(directory)
        val original = script(SCRIPT_ID, "Original")
        firstStore.save(original)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val futures = listOf(
            executor.submit<RecordingSaveResult> {
                start.await()
                firstStore.save(original.copy(name = "First"), expectedRevision = 1)
            },
            executor.submit<RecordingSaveResult> {
                start.await()
                secondStore.save(original.copy(name = "Second"), expectedRevision = 1)
            },
        )
        start.countDown()
        val results = futures.map { it.get(5, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, results.count { it is RecordingSaveResult.Saved })
        assertEquals(1, results.count { it is RecordingSaveResult.Conflict })
        val current = firstStore.get(SCRIPT_ID)
        assertEquals(2L, current?.revision)
        val conflict = results.filterIsInstance<RecordingSaveResult.Conflict>().single()
        assertEquals(current, conflict.current)
        assertTrue(conflict.attempted.name in setOf("First", "Second"))
    }

    @Test
    fun `atomic replacement failure preserves the previous revision`() {
        val directory = temporaryFolder.newFolder("atomic-failure")
        val operations = FailingRecordingFileOperations()
        val store = RecordingScriptStore(directory, fileOperations = operations)
        val original = script(SCRIPT_ID, "Original")
        store.save(original)
        val before = File(directory, "$SCRIPT_ID.json").readText()
        operations.replaceFailure = IOException("replace failed")

        assertThrows(RecordingStorageException::class.java) {
            store.save(original.copy(name = "Must not persist"))
        }

        assertEquals(before, File(directory, "$SCRIPT_ID.json").readText())
        assertEquals(original, RecordingScriptStore(directory).get(SCRIPT_ID))
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `successful update retains the previous revision as a recovery backup`() {
        val directory = temporaryFolder.newFolder("backup")
        val store = RecordingScriptStore(directory)
        val original = script(SCRIPT_ID, "Original")
        store.save(original)

        val result = store.save(
            original.copy(name = "Updated"),
            expectedRevision = original.revision,
        )

        assertTrue(result is RecordingSaveResult.Saved)
        val backup = File(directory, "$SCRIPT_ID.json.bak")
        assertTrue(backup.isFile)
        assertEquals(original, store.importScript(backup.readText()))
        assertEquals("Updated", store.get(SCRIPT_ID)?.name)
    }

    @Test
    fun `external OS lock contention times out without reading or overwriting data`() {
        val directory = temporaryFolder.newFolder("lock-timeout")
        val original = script(SCRIPT_ID, "Original")
        RecordingScriptStore(directory).save(original)
        val lockFile = File(directory, ".$SCRIPT_ID.json.lock")
        val store = RecordingScriptStore(
            directory = directory,
            fileOperations = SystemRecordingFileOperations,
            lockTimeoutMs = 25,
        )

        RandomAccessFile(lockFile, "rw").use { access ->
            access.channel.lock().use {
                val error = assertThrows(RecordingStorageException::class.java) {
                    store.save(
                        original.copy(name = "Must not persist"),
                        expectedRevision = original.revision,
                    )
                }
                assertTrue(error.message.orEmpty().contains("recording script lock"))
            }
        }

        assertEquals(original, RecordingScriptStore(directory).get(SCRIPT_ID))
    }

    @Test
    fun `rollback cleanup failure is reported without overwriting the original`() {
        val directory = temporaryFolder.newFolder("rollback-failure")
        val operations = FailingRecordingFileOperations()
        val store = RecordingScriptStore(directory, fileOperations = operations)
        val original = script(SCRIPT_ID, "Original")
        store.save(original)
        val before = File(directory, "$SCRIPT_ID.json").readText()
        operations.replaceFailure = IOException("replace failed")
        operations.deleteFailure = IOException("cleanup failed")

        val error = assertThrows(RecordingStorageException::class.java) {
            store.save(original.copy(name = "Must not persist"))
        }

        assertEquals(before, File(directory, "$SCRIPT_ID.json").readText())
        assertTrue(error.cause?.suppressed.orEmpty().any { it.message == "cleanup failed" })
    }

    @Test
    fun `failed migration rewrite preserves the original version 1_0 file`() {
        val directory = temporaryFolder.newFolder("migration-rollback")
        val file = File(directory, "$SCRIPT_ID.json")
        val original = fixture("automation-script-v1.0.json")
        file.writeText(original)
        val operations = FailingRecordingFileOperations().apply {
            replaceFailure = IOException("replace failed")
        }
        val store = RecordingScriptStore(directory, fileOperations = operations)

        assertThrows(RecordingStorageException::class.java) {
            store.get(SCRIPT_ID)
        }

        assertEquals(original, file.readText())
    }

    @Test
    fun `default export removes secrets screenshots and device local paths`() {
        val store = RecordingScriptStore(temporaryFolder.newFolder("safe-export"))
        val sensitive = script(SCRIPT_ID, "Sensitive").copy(
            variables = listOf(
                ScriptVariable(
                    name = "password",
                    type = "secret",
                    sensitive = true,
                    defaultValue = JsonPrimitive("plain-secret"),
                ),
                ScriptVariable(
                    name = "publicValue",
                    type = "string",
                    sensitive = false,
                    defaultValue = JsonPrimitive("safe-value"),
                ),
            ),
            steps = listOf(
                script(SCRIPT_ID, "Sensitive").steps.single().copy(
                    provenance = RecordingProvenance.VISUAL,
                    action = RecordedAction(
                        type = "ui.setText",
                        params = JsonObject(
                            mapOf(
                                "target" to JsonObject(
                                    mapOf(
                                        "normalizedScreenPoint" to JsonObject(
                                            mapOf(
                                                "x" to JsonPrimitive(0.5),
                                                "y" to JsonPrimitive(0.5),
                                            ),
                                        ),
                                        "fingerprint" to JsonObject(
                                            mapOf(
                                                "password" to JsonPrimitive("nested-secret"),
                                                "localPath" to JsonPrimitive(
                                                    "/sdcard/private/screen.png",
                                                ),
                                                "role" to JsonPrimitive("textField"),
                                            ),
                                        ),
                                    ),
                                ),
                                "secretRef" to JsonPrimitive("password"),
                            ),
                        ),
                    ),
                    visualTarget = VisualTarget(
                        normalizedPoint = NormalizedPoint(0.5, 0.5),
                        confidence = 0.9,
                        source = RecordingProvenance.VISUAL,
                        screenshotBase64 = "c2NyZWVuc2hvdA==",
                        deviceLocalPath = "/data/user/0/dev.aiauto.android/cache/screen.png",
                    ),
                ),
            ),
        )

        val exported = store.exportScript(sensitive)
        val imported = store.importScript(exported)

        assertFalse(exported.contains("plain-secret"))
        assertFalse(exported.contains("nested-secret"))
        assertFalse(exported.contains("c2NyZWVuc2hvdA=="))
        assertFalse(exported.contains("/data/user/0/"))
        assertFalse(exported.contains("/sdcard/private/"))
        assertTrue(exported.contains("safe-value"))
        assertTrue(exported.contains("secretRef"))
        assertTrue(exported.contains("textField"))
        assertNull(imported.variables.first { it.name == "password" }.defaultValue)
        assertNull(imported.steps.single().visualTarget?.screenshotBase64)
        assertNull(imported.steps.single().visualTarget?.deviceLocalPath)
    }

    @Test
    fun `text actions cannot contain both literal and secret input`() {
        val directory = temporaryFolder.newFolder("invalid-secret")
        val store = RecordingScriptStore(directory)
        val invalid = script(SCRIPT_ID, "Invalid").copy(
            steps = listOf(
                RecordedStep(
                    id = STEP_ID,
                    recordedAtMs = 0,
                    action = RecordedAction(
                        type = "ui.setText",
                        params = JsonObject(
                            mapOf(
                                "text" to kotlinx.serialization.json.JsonPrimitive("leak"),
                                "secretRef" to
                                    kotlinx.serialization.json.JsonPrimitive("password"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            store.save(invalid)
        }
    }

    @Test
    fun `script ids cannot escape the recording directory`() {
        val store = RecordingScriptStore(temporaryFolder.newFolder("safe"))

        assertThrows(IllegalArgumentException::class.java) {
            store.get("../outside")
        }
    }

    @Test
    fun `summary contract rejects invalid package requirements and identifiers`() {
        val store = RecordingScriptStore(temporaryFolder.newFolder("contract"))
        val valid = script(SCRIPT_ID, "Valid")
        val invalidScripts = listOf(
            valid.copy(id = "not-a-uuid"),
            valid.copy(targetPackages = listOf("com.example;bad")),
            valid.copy(requirements = ScriptRequirements(minApiLevel = 29)),
            valid.copy(
                requirements = ScriptRequirements(
                    capabilities = listOf("accessibility.action", "accessibility.action"),
                ),
            ),
            valid.copy(steps = valid.steps.map { it.copy(id = "not-a-uuid") }),
        )

        invalidScripts.forEach { script ->
            assertThrows(IllegalArgumentException::class.java) {
                store.save(script)
            }
        }
    }

    private fun script(
        id: String,
        name: String,
    ) = AutomationScript(
        id = id,
        name = name,
        targetPackages = listOf("com.example"),
        createdAt = "2026-07-18T00:00:00Z",
        steps = listOf(
            RecordedStep(
                id = STEP_ID,
                recordedAtMs = 0,
                action = RecordedAction(
                    type = "ui.back",
                    params = JsonObject(emptyMap()),
                ),
            ),
        ),
    )

    private fun fixture(name: String): String {
        val userDirectory = requireNotNull(System.getProperty("user.dir")) {
            "user.dir is required to locate protocol fixtures"
        }
        val start = File(userDirectory)
        val fixture = generateSequence(start, File::getParentFile)
            .map { directory -> File(directory, "protocol/fixtures/$name") }
            .firstOrNull(File::isFile)
        return requireNotNull(fixture) {
            "Unable to locate protocol fixture $name from ${start.absolutePath}"
        }.readText()
    }

    private class FailingRecordingFileOperations :
        RecordingFileOperations by SystemRecordingFileOperations {
        var replaceFailure: IOException? = null
        var deleteFailure: IOException? = null

        override fun replaceAtomically(
            source: File,
            destination: File,
        ) {
            replaceFailure?.let { throw it }
            SystemRecordingFileOperations.replaceAtomically(source, destination)
        }

        override fun delete(file: File): Boolean {
            deleteFailure?.let { throw it }
            return SystemRecordingFileOperations.delete(file)
        }
    }

    private companion object {
        const val SCRIPT_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val STEP_ID = "123e4567-e89b-42d3-a456-426614174001"
        const val FIXTURE_SCRIPT_ID = "ec43ff73-c501-4c55-8785-9a812f009c1e"
    }
}
