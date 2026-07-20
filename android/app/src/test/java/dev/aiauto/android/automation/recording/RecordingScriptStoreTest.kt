package dev.aiauto.android.automation.recording

/**
 * 测试用途：验证 RecordingScriptStore 的功能契约、失败语义及自动化安全边界。
 */

import java.io.File

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

        store.save(script)

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
    fun `legacy schema is migrated and rewritten only after successful decode`() {
        val directory = temporaryFolder.newFolder("legacy")
        val file = File(directory, "$SCRIPT_ID.json")
        file.writeText(
            """
            {
              "id": "$SCRIPT_ID",
              "schemaVersion": "0.9",
              "title": "Imported",
              "packages": ["com.example"],
              "createdAt": "2026-07-18T00:00:00Z",
              "steps": [{
                "id": "$STEP_ID",
                "recordedAtMs": 10,
                "action": {"type": "ui.back", "params": {}}
              }]
            }
            """.trimIndent(),
        )
        val store = RecordingScriptStore(directory)

        val migrated = store.get(SCRIPT_ID)

        assertNotNull(migrated)
        assertEquals(RECORDING_SCHEMA_VERSION, migrated?.schemaVersion)
        assertEquals("Imported", migrated?.name)
        assertEquals(2, migrated?.steps?.single()?.retry?.maxAttempts)
        assertTrue(file.readText().contains("\"schemaVersion\": \"1.0\""))
    }

    @Test
    fun `unknown schema version is rejected without rewriting the source`() {
        val directory = temporaryFolder.newFolder("unknown")
        val file = File(directory, "$SCRIPT_ID.json")
        val original = """
            {"id":"$SCRIPT_ID","schemaVersion":"9.0"}
        """.trimIndent()
        file.writeText(original)
        val store = RecordingScriptStore(directory)

        assertThrows(RecordingStorageException::class.java) {
            store.get(SCRIPT_ID)
        }
        assertEquals(original, file.readText())
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

    private companion object {
        const val SCRIPT_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val STEP_ID = "123e4567-e89b-42d3-a456-426614174001"
    }
}
