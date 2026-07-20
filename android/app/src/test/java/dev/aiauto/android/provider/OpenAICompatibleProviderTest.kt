package dev.aiauto.android.provider

/**
 * 测试用途：验证 OpenAICompatibleProvider 的功能契约、失败语义及自动化安全边界。
 */

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAICompatibleProviderTest {
    private val config = ProviderConfig(
        baseUrl = "https://provider.example.com/v1/",
        model = "test-model",
        timeoutSeconds = 12,
    )
    private val apiKey = "sk-test-secret"

    @Test
    fun `plans an action through the OpenAI compatible endpoint`() = runTest {
        val connection = FakeHttpURLConnection(
            responseCodeValue = 200,
            responseBody = completionResponse(
                """{"type":"task.finish","params":{"summary":"done"}}""",
            ),
        )
        var requestedUri: URI? = null
        val provider = provider(connection) { requestedUri = it }

        val result = provider.planNextAction(
            AutomationPrompt(
                task = "Open settings",
                uiSummary = "Launcher is visible",
                previousActionSummary = "Pressed home",
            ),
        )

        assertEquals("task.finish", result.action.type)
        assertEquals("https://provider.example.com/v1/chat/completions", requestedUri.toString())
        assertEquals("POST", connection.requestMethod)
        assertEquals(12_000, connection.connectTimeout)
        assertEquals(12_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("Bearer $apiKey", connection.requestHeaders["Authorization"])
        assertEquals("application/json", connection.requestHeaders["Accept"])
        val requestBody = connection.requestBody.toString(StandardCharsets.UTF_8)
        assertTrue(requestBody.contains("test-model"))
        assertTrue(requestBody.contains("Open settings"))
        assertFalse(requestBody.contains(apiKey))
        assertTrue(connection.disconnected)
    }

    @Test
    fun `sends an authorized screenshot as a bounded low detail png BitsUT`() = runTest {
        val connection = FakeHttpURLConnection(
            responseCodeValue = 200,
            responseBody = completionResponse(
                """{"type":"task.finish","params":{"summary":"done"}}""",
            ),
        )

        provider(connection).planNextAction(
            AutomationPrompt(
                task = "Inspect",
                uiSummary = "One non-sensitive button",
                screenshotPng = byteArrayOf(1, 2, 3),
            ),
        )

        val requestBody = connection.requestBody.toString(StandardCharsets.UTF_8)
        assertTrue(requestBody.contains("\"type\":\"image_url\""))
        assertTrue(requestBody.contains("\"detail\":\"low\""))
        assertTrue(requestBody.contains("data:image/png;base64,AQID"))
    }

    @Test
    fun `rejects screenshots larger than one mebibyte before opening connection BitsUT`() =
        runTest {
            var connectionOpened = false
            val provider = OpenAICompatibleProvider(
                config = config,
                apiKey = apiKey,
                actionParser = ProviderActionParser(),
                connectionFactory = {
                    connectionOpened = true
                    error("connection must not be opened")
                },
            )

            try {
                provider.planNextAction(
                    AutomationPrompt(
                        task = "Inspect",
                        uiSummary = "Safe UI",
                        screenshotPng = ByteArray(1_048_577),
                    ),
                )
                fail("Expected oversized screenshot rejection")
            } catch (error: java.io.IOException) {
                assertTrue(error.message!!.contains("exceeds 1048576 bytes"))
            }
            assertFalse(connectionOpened)
        }

    @Test
    fun `connection test requires a strictly valid finish action`() = runTest {
        val validConnection = FakeHttpURLConnection(
            responseCodeValue = 200,
            responseBody = completionResponse(
                """{"type":"task.finish","params":{"summary":"ok"}}""",
            ),
        )
        val invalidConnection = FakeHttpURLConnection(
            responseCodeValue = 200,
            responseBody = completionResponse(
                """{"type":"shell.exec","params":{}}""",
            ),
        )

        assertEquals(
            ProviderConnectionResult.Success,
            provider(validConnection).testConnection(),
        )
        val failure = provider(invalidConnection).testConnection()
        assertTrue(failure is ProviderConnectionResult.Failure)
        assertTrue(
            (failure as ProviderConnectionResult.Failure).message
                .contains("Unsupported action type"),
        )
    }

    @Test
    fun `redacts secrets from provider errors`() = runTest {
        val connection = FakeHttpURLConnection(
            responseCodeValue = 401,
            errorBody = """{"error":{"message":"Bearer $apiKey was rejected"}}""",
        )

        val result = provider(connection).testConnection()

        assertTrue(result is ProviderConnectionResult.Failure)
        val message = (result as ProviderConnectionResult.Failure).message
        assertFalse(message.contains(apiKey))
        assertTrue(message.contains("[REDACTED]"))
        assertTrue(connection.disconnected)
    }

    @Test
    fun `rejects oversized provider responses`() = runTest {
        val connection = FakeHttpURLConnection(
            responseCodeValue = 200,
            responseBody = "x".repeat(1_048_577),
        )

        val result = provider(connection).testConnection()

        assertTrue(result is ProviderConnectionResult.Failure)
        assertTrue(
            (result as ProviderConnectionResult.Failure).message
                .contains("exceeds 1048576 bytes"),
        )
        assertTrue(connection.disconnected)
    }

    @Test
    fun `propagates coroutine cancellation`() = runTest {
        val provider = OpenAICompatibleProvider(
            config = config,
            apiKey = apiKey,
            actionParser = ProviderActionParser(),
            connectionFactory = { throw CancellationException("cancelled") },
        )

        try {
            provider.testConnection()
            fail("Expected cancellation to propagate")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }
    }

    private fun provider(
        connection: FakeHttpURLConnection,
        onRequest: (URI) -> Unit = {},
    ) = OpenAICompatibleProvider(
        config = config,
        apiKey = apiKey,
        actionParser = ProviderActionParser(),
        connectionFactory = { uri ->
            onRequest(uri)
            connection
        },
    )

    private fun completionResponse(content: String): String {
        val escapedContent = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "$escapedContent"
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private class FakeHttpURLConnection(
        responseCodeValue: Int,
        private val responseBody: String = "",
        private val errorBody: String = "",
    ) : HttpURLConnection(URL("https://provider.example.com")) {
        val requestBody = ByteArrayOutputStream()
        val requestHeaders = mutableMapOf<String, String>()
        var disconnected = false

        init {
            responseCode = responseCodeValue
        }

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getOutputStream(): ByteArrayOutputStream = requestBody

        override fun getInputStream(): InputStream =
            ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8))

        override fun getErrorStream(): InputStream =
            ByteArrayInputStream(errorBody.toByteArray(StandardCharsets.UTF_8))

        override fun setRequestProperty(key: String, value: String) {
            requestHeaders[key] = value
        }
    }
}
