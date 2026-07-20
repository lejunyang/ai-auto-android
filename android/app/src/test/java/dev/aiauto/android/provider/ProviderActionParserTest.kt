package dev.aiauto.android.provider

/**
 * 测试用途：验证 ProviderActionParser 的功能契约、失败语义及自动化安全边界。
 */

import kotlinx.serialization.json.jsonPrimitive
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
            "x must be between",
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
