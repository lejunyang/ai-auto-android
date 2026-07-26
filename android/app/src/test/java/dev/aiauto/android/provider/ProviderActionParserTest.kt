package dev.aiauto.android.provider

/**
 * 测试用途：验证 ProviderActionParser 的功能契约、失败语义及自动化安全边界。
 */

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderActionParserTest {
    private val parser = ProviderActionParser()

    @Test
    fun `parses a schema compliant selector action`() {
        val action = parser.parse(
            """
                {
                  "type": "ui.click",
                  "params": {
                    "target": {
                      "packageName": "com.example.app",
                      "selectorCandidates": [
                        {
                          "strategy": "resourceId",
                          "value": "com.example.app:id/continue_button",
                          "weight": 1.0,
                          "required": true
                        }
                      ],
                      "fingerprint": {
                        "className": "android.widget.Button",
                        "clickable": true
                      }
                    }
                  }
                }
            """.trimIndent(),
        )

        assertEquals("ui.click", action.type)
        assertEquals(
            "com.example.app",
            action.params["target"]
                ?.let { it as kotlinx.serialization.json.JsonObject }
                ?.get("packageName")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `parses a coordinate fallback action`() {
        val action = parser.parse(
            """
                {
                  "type": "ui.setText",
                  "params": {
                    "target": {
                      "recordedBounds": {
                        "left": 10,
                        "top": 20,
                        "right": 200,
                        "bottom": 100
                      },
                      "relativePoint": {"x": 0.5, "y": 0.25}
                    },
                    "secretRef": "account.password"
                  }
                }
            """.trimIndent(),
        )

        assertEquals("ui.setText", action.type)
        assertEquals(
            "account.password",
            action.params["secretRef"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `parses strict visual tap bound to authorized observation`() {
        val action = parser.parse(
            """
                {
                  "type": "ui.tap",
                  "params": {
                    "visualTarget": {
                      "packageName": "com.example.app",
                      "observationId": "123e4567-e89b-42d3-a456-426614174044",
                      "imageSha256": "${"a".repeat(64)}",
                      "candidateId": "123e4567-e89b-42d3-a456-426614174045",
                      "source": "model",
                      "confidence": 0.93,
                      "point": {"x": 0.5, "y": 0.75},
                      "bounds": {"left": 0.4, "top": 0.7, "right": 0.6, "bottom": 0.8}
                    }
                  }
                }
            """.trimIndent(),
        )

        assertEquals("ui.tap", action.type)
        assertEquals(
            "123e4567-e89b-42d3-a456-426614174044",
            action.params["visualTarget"]
                ?.let { it as kotlinx.serialization.json.JsonObject }
                ?.get("observationId")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `parses visual swipe endpoints only as normalized candidate relative points`() {
        val action = parser.parse(
            """
                {
                  "type": "ui.swipe",
                  "params": {
                    "visualTarget": {
                      "packageName": "com.example.app",
                      "observationId": "123e4567-e89b-42d3-a456-426614174044",
                      "imageSha256": "${"a".repeat(64)}",
                      "candidateId": "123e4567-e89b-42d3-a456-426614174045",
                      "source": "model",
                      "confidence": 0.93,
                      "point": {"x": 0.5, "y": 0.75},
                      "bounds": {"left": 0.4, "top": 0.7, "right": 0.6, "bottom": 0.8}
                    },
                    "start": {"x": 0.0, "y": 0.0},
                    "end": {"x": 1.0, "y": 1.0},
                    "durationMs": 450
                  }
                }
            """.trimIndent(),
        )

        assertEquals("ui.swipe", action.type)
        assertEquals(
            1.0,
            requireNotNull(
                action.params["end"]
                    ?.let { it as kotlinx.serialization.json.JsonObject }
                    ?.get("x")
                    ?.jsonPrimitive
                    ?.double,
            ),
            0.0,
        )
        assertRejected(
            """
                {
                  "type":"ui.swipe",
                  "params":{
                    "visualTarget":${action.params.getValue("visualTarget")},
                    "start":{"x":100,"y":200},
                    "end":{"x":300,"y":400},
                    "durationMs":450
                  }
                }
            """.trimIndent(),
            "start.x",
        )
    }

    @Test
    fun `accepts null scalar values allowed by the action schema`() {
        assertEquals(
            "ui.wait",
            parser.parse(
                """
                    {
                      "type": "ui.wait",
                      "params": {
                        "kind": "window",
                        "operator": "equals",
                        "expected": null
                      }
                    }
                """.trimIndent(),
            ).type,
        )
        assertEquals(
            "ui.click",
            parser.parse(
                """
                    {
                      "type": "ui.click",
                      "params": {
                        "target": {
                          "selectorCandidates": [
                            {"strategy":"role","value":"button","weight":1.0}
                          ],
                          "fingerprint": {"text": null}
                        }
                      }
                    }
                """.trimIndent(),
            ).type,
        )
    }

    @Test
    fun `rejects unknown actions and extra root fields`() {
        assertRejected(
            """{"type":"shell.exec","params":{}}""",
            "Unsupported action type",
        )
        assertRejected(
            """{"type":"ui.back","params":{},"reason":"trust me"}""",
            "Unsupported fields",
        )
    }

    @Test
    fun `rejects non string action types`() {
        assertRejected(
            """{"type":42,"params":{}}""",
            "type must be a string",
        )
    }

    @Test
    fun `rejects missing fields and extra nested fields`() {
        assertRejected(
            """{"type":"ui.back"}""",
            "Missing fields: params",
        )
        assertRejected(
            """
                {
                  "type": "ui.click",
                  "params": {
                    "target": {
                      "normalizedScreenPoint": {"x": 0.5, "y": 0.5},
                      "unsafeSelector": "*"
                    }
                  }
                }
            """.trimIndent(),
            "Target contains unsupported fields",
        )
    }

    @Test
    fun `rejects malformed selector candidates`() {
        assertRejected(
            """
                {
                  "type": "ui.click",
                  "params": {
                    "target": {
                      "selectorCandidates": [
                        {"strategy":"xpath","value":"//button","weight":0.8}
                      ]
                    }
                  }
                }
            """.trimIndent(),
            "Unsupported selector strategy",
        )
        assertRejected(
            """
                {
                  "type": "ui.click",
                  "params": {
                    "target": {
                      "selectorCandidates": "not-an-array"
                    }
                  }
                }
            """.trimIndent(),
            "selectorCandidates must be an array",
        )
    }

    @Test
    fun `rejects out of range coordinate fallbacks`() {
        assertRejected(
            """
                {
                  "type": "ui.tap",
                  "params": {"x": -1, "y": 100}
                }
            """.trimIndent(),
            "visualTarget",
        )
        assertRejected(
            """
                {
                  "type": "ui.click",
                  "params": {
                    "target": {
                      "normalizedScreenPoint": {"x": 1.1, "y": 0.5}
                    }
                  }
                }
            """.trimIndent(),
            "x must be between",
        )
    }

    @Test
    fun `rejects visual evidence drift low confidence and point outside bounds`() {
        val base = """
            {
              "type":"ui.tap",
              "params":{"visualTarget":{
                "packageName":"com.example.app",
                "observationId":"123e4567-e89b-42d3-a456-426614174044",
                "imageSha256":"${"a".repeat(64)}",
                "candidateId":"123e4567-e89b-42d3-a456-426614174045",
                "source":"model",
                "confidence":0.93,
                "point":{"x":0.5,"y":0.75},
                "bounds":{"left":0.4,"top":0.7,"right":0.6,"bottom":0.8}
              }}
            }
        """.trimIndent()
        for ((invalid, message) in listOf(
            base.replace("\"${"a".repeat(64)}\"", "\"${"A".repeat(64)}\"") to "SHA-256",
            base.replace("\"source\":\"model\"", "\"source\":\"manual\"") to "source",
            base.replace("\"confidence\":0.93", "\"confidence\":0.69") to "confidence",
            base.replace("\"x\":0.5", "\"x\":0.9") to "inside bounds",
            base.replace(
                "\"candidateId\":\"123e4567-e89b-42d3-a456-426614174045\"",
                "\"candidateId\":\"not-a-uuid\"",
            ) to "UUID",
        )) {
            assertRejected(invalid, message)
        }
    }

    @Test
    fun `requires exactly one set text value source`() {
        val target = """"target":{"normalizedScreenPoint":{"x":0.5,"y":0.5}}"""

        assertRejected(
            """{"type":"ui.setText","params":{$target}}""",
            "exactly one",
        )
        assertRejected(
            """{"type":"ui.setText","params":{$target,"text":"visible","secretRef":"secret"}}""",
            "exactly one",
        )
    }

    private fun assertRejected(rawAction: String, expectedMessage: String) {
        val error = assertThrows(ProviderActionParseException::class.java) {
            parser.parse(rawAction)
        }
        assertTrue(
            "Expected '${error.message}' to contain '$expectedMessage'",
            error.message.orEmpty().contains(expectedMessage),
        )
    }
}
