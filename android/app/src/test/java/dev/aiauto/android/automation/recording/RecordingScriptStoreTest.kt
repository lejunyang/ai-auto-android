package dev.aiauto.android.automation.recording

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
        val script = script("script-1", "First")

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
        val file = File(directory, "legacy.json")
        file.writeText(
            """
            {
              "id": "legacy",
              "schemaVersion": "0.9",
              "title": "Imported",
              "packages": ["com.example"],
              "createdAt": "2026-07-18T00:00:00Z",
              "steps": [{
                "id": "step-1",
                "recordedAtMs": 10,
                "action": {"type": "ui.back", "params": {}}
              }]
            }
            """.trimIndent(),
        )
        val store = RecordingScriptStore(directory)

        val migrated = store.get("legacy")

        assertNotNull(migrated)
        assertEquals(RECORDING_SCHEMA_VERSION, migrated?.schemaVersion)
        assertEquals("Imported", migrated?.name)
        assertEquals(2, migrated?.steps?.single()?.retry?.maxAttempts)
        assertTrue(file.readText().contains("\"schemaVersion\": \"1.0\""))
    }

    @Test
    fun `unknown schema version is rejected without rewriting the source`() {
        val directory = temporaryFolder.newFolder("unknown")
        val file = File(directory, "future.json")
        val original = """
            {"id":"future","schemaVersion":"9.0"}
        """.trimIndent()
        file.writeText(original)
        val store = RecordingScriptStore(directory)

        assertThrows(RecordingStorageException::class.java) {
            store.get("future")
        }
        assertEquals(original, file.readText())
    }

    @Test
    fun `text actions cannot contain both literal and secret input`() {
        val directory = temporaryFolder.newFolder("invalid-secret")
        val store = RecordingScriptStore(directory)
        val invalid = script("invalid", "Invalid").copy(
            steps = listOf(
                RecordedStep(
                    id = "step",
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
                id = "step-1",
                recordedAtMs = 0,
                action = RecordedAction(
                    type = "ui.back",
                    params = JsonObject(emptyMap()),
                ),
            ),
        ),
    )
}
