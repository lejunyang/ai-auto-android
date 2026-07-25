package dev.aiauto.android.observe.visual

/**
 * 测试用途：验证 Android 编码与独立 visual schema canonical example 完全一致。
 */

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VisualSchemaContractTest {
    @Test
    fun `android canonical bundle equals standalone schema example BitsUT`() {
        val schema = Json.parseToJsonElement(
            fixture("visual-observation-proposal.schema.json"),
        ).jsonObject
        val expected = schema.getValue("examples").jsonArray.single()

        val encoded = VisualJson.encodeBundle(
            VisualJson.decodeBundle(Json.encodeToString(expected)),
        )

        assertEquals(expected, Json.parseToJsonElement(encoded))
        assertFalse(encoded.contains("screenshotBase64"))
        assertFalse(encoded.contains("pngBytes"))
        assertFalse(encoded.contains("filePath"))
        assertFalse(encoded.contains("secret"))
    }

    @Test
    fun `sensitive schema node cannot carry a label BitsUT`() {
        val schema = Json.parseToJsonElement(
            fixture("visual-observation-proposal.schema.json"),
        ).jsonObject
        val sensitive = schema
            .getValue("examples").jsonArray.single().jsonObject
            .getValue("observation").jsonObject
            .getValue("hierarchy").jsonArray[1].jsonObject

        assertEquals(setOf("role", "bounds", "sensitive", "children"), sensitive.keys)
        val encoded = VisualJson.encodeBundle(
            VisualJson.decodeBundle(
                Json.encodeToString(
                    JsonObject(
                        schema.getValue("examples").jsonArray
                            .single().jsonObject
                            .toMutableMap(),
                    ),
                ),
            ),
        )
        assertFalse(encoded.contains("password\":\""))
    }

    private companion object {
        fun fixture(name: String): String {
            val start = requireNotNull(System.getProperty("user.dir")) {
                "user.dir is required to locate visual schema"
            }.let(::File)
            val file = generateSequence(start, File::getParentFile)
                .map { directory -> File(directory, "protocol/schema/v1/$name") }
                .firstOrNull(File::isFile)
            return requireNotNull(file) {
                "Unable to locate visual schema $name from ${start.absolutePath}"
            }.readText()
        }
    }
}
