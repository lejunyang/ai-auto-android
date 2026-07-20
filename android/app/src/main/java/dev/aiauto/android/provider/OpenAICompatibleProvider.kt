package dev.aiauto.android.provider

/**
 * 功能用途：实现 OpenAICompatibleProvider 对应的 AI Provider 配置、调用、动作解析或敏感日志保护。
 */

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ProviderHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)

class OpenAICompatibleProvider(
    private val config: ProviderConfig,
    private val apiKey: String,
    private val actionParser: ProviderActionParser,
    private val connectionFactory: (URI) -> HttpURLConnection = { uri ->
        uri.toURL().openConnection() as HttpURLConnection
    },
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : AutomationProvider {
    override suspend fun planNextAction(prompt: AutomationPrompt): ProviderResult {
        val request = ChatCompletionRequest(
            model = config.model,
            messages = listOf(
                ChatRequestMessage(role = "system", content = JsonPrimitive(SYSTEM_PROMPT)),
                ChatRequestMessage(role = "user", content = prompt.toUserContent()),
            ),
            temperature = 0.0,
            responseFormat = ResponseFormat(type = "json_object"),
        )
        val response = executeRequest(request)
        val content = response.requireContent()
        return ProviderResult(
            action = actionParser.parse(content),
            rawContent = content,
        )
    }

    override suspend fun testConnection(): ProviderConnectionResult {
        return try {
            val response = executeRequest(
                ChatCompletionRequest(
                    model = config.model,
                    messages = listOf(
                        ChatRequestMessage(
                            role = "user",
                            content = JsonPrimitive(
                                """Return {"type":"task.finish","params":{"summary":"ok"}}""",
                            ),
                        ),
                    ),
                    temperature = 0.0,
                    maxTokens = 64,
                    responseFormat = ResponseFormat(type = "json_object"),
                ),
            )
            val action = actionParser.parse(response.requireContent())
            if (action.type != "task.finish") {
                throw IOException("Provider connection check returned an unexpected action")
            }
            ProviderConnectionResult.Success
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ProviderConnectionResult.Failure(
                SensitiveLogRedactor.redact(error.message ?: "Connection failed", apiKey),
            )
        }
    }

    private suspend fun executeRequest(request: ChatCompletionRequest): ChatCompletionResponse =
        withContext(Dispatchers.IO) {
            val endpoint = URI.create(
                "${ProviderConfigValidator.normalizeBaseUrl(config.baseUrl)}/chat/completions",
            )
            val connection = connectionFactory(endpoint)
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = config.timeoutSeconds * 1_000
                connection.readTimeout = config.timeoutSeconds * 1_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val requestBody = json.encodeToString(request)
                    .toByteArray(StandardCharsets.UTF_8)
                if (requestBody.size > MAX_REQUEST_BYTES) {
                    requestBody.fill(0)
                    throw IOException("Provider request exceeds $MAX_REQUEST_BYTES bytes")
                }
                try {
                    connection.outputStream.use { output -> output.write(requestBody) }
                } finally {
                    requestBody.fill(0)
                }

                val status = connection.responseCode
                val body = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                    ?.use { it.readUtf8(MAX_RESPONSE_BYTES) }
                    .orEmpty()
                if (status !in 200..299) {
                    val safeMessage = SensitiveLogRedactor.redact(
                        parseProviderError(body) ?: "Provider request failed with HTTP $status",
                        apiKey,
                    )
                    throw ProviderHttpException(statusCode = status, message = safeMessage)
                }
                runCatching { json.decodeFromString<ChatCompletionResponse>(body) }
                    .getOrElse { throw IOException("Provider returned malformed JSON", it) }
            } finally {
                connection.disconnect()
            }
        }

    private fun parseProviderError(body: String): String? =
        runCatching { json.decodeFromString<ProviderErrorEnvelope>(body).error.message }.getOrNull()

    private fun ChatCompletionResponse.requireContent(): String =
        choices.firstOrNull()?.message?.content
            ?: throw IOException("Provider returned no choices")

    private fun AutomationPrompt.toUserContent(): JsonElement {
        val text = buildString {
            appendLine("Task:")
            appendLine(task)
            appendLine("Current UI summary:")
            appendLine(uiSummary)
            previousActionSummary?.let {
                appendLine("Previous action:")
                appendLine(it)
            }
        }
        val screenshot = screenshotPng ?: return JsonPrimitive(text)
        if (screenshot.size > MAX_SCREENSHOT_BYTES) {
            throw IOException("Provider screenshot exceeds $MAX_SCREENSHOT_BYTES bytes")
        }
        val dataUrl = "data:image/png;base64,${Base64.getEncoder().encodeToString(screenshot)}"
        return JsonArray(
            listOf(
                requestContent("text", "text", JsonPrimitive(text)),
                requestContent(
                    "image_url",
                    "image_url",
                    buildJsonObject {
                        put("url", JsonPrimitive(dataUrl))
                        put("detail", JsonPrimitive("low"))
                    },
                ),
            ),
        )
    }

    private companion object {
        const val MAX_SCREENSHOT_BYTES = 1_048_576
        const val MAX_REQUEST_BYTES = 2_097_152
        const val MAX_RESPONSE_BYTES = 1_048_576

        val SYSTEM_PROMPT = """
            You plan exactly one Android automation action.
            Return one JSON object with only "type" and "params".
            Allowed types: app.launch, app.stop, ui.click, ui.longClick, ui.tap, ui.swipe,
            ui.setText, ui.scroll, ui.back, ui.home, ui.recents, ui.wait, ui.assert, task.finish.
            Do not include markdown, prose, credentials, or additional fields.
        """.trimIndent()
    }
}

private fun InputStream.readUtf8(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) {
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        }
        totalBytes += read
        if (totalBytes > maxBytes) {
            throw IOException("Provider response exceeds $maxBytes bytes")
        }
        output.write(buffer, 0, read)
    }
}

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatRequestMessage>,
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat,
)

@Serializable
private data class ChatRequestMessage(
    val role: String,
    val content: JsonElement,
)

@Serializable
private data class ResponseFormat(
    val type: String,
)

@Serializable
private data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
private data class ChatChoice(
    val message: ChatResponseMessage,
)

@Serializable
private data class ChatResponseMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ProviderErrorEnvelope(
    val error: ProviderError,
)

@Serializable
private data class ProviderError(
    val message: String,
)

private fun requestContent(
    type: String,
    contentName: String,
    content: JsonElement,
): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type))
    put(contentName, content)
}
