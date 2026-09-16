package dev.aiauto.android.automation.recording.editor.language

/**
 * 功能用途：基于已配置的 OpenAI 兼容 Provider，为录制脚本生成自然语言描述草稿。
 *
 * 设计边界：
 * - 本类只负责"生成建议"，不回写 AutomationScript 的步骤或已有描述，是否采纳由调用方决定。
 * - 发送给模型的步骤摘要只保留动作类型和非敏感目标标识，文本输入内容、secretRef 引用值、
 *   视觉目标截图均不外发，避免把口令、密码或 UI 截图泄露给第三方模型。
 * - Provider 未配置、网络失败或超时时返回 sealed 结果，不抛出崩溃。
 */

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import dev.aiauto.android.provider.ProviderConfig
import dev.aiauto.android.provider.ProviderConfigValidator
import dev.aiauto.android.provider.ProviderHttpException
import dev.aiauto.android.provider.SensitiveLogRedactor

/**
 * 语言生成结果的统一出口。
 *
 * - [Success]：成功生成描述，[changedFromPrevious] 标识新描述与已有描述是否不同，
 *   调用方可据此提示用户"意图已改，步骤尚未同步"。
 * - [NotConfigured]：Provider 未配置（无 API Key），调用方应引导用户前往配置页。
 * - [Error]：网络/API 失败或超时，[message] 已做敏感信息脱敏。
 */
sealed interface LanguageGenerationResult {
    data class Success(
        val description: String,
        val changedFromPrevious: Boolean,
    ) : LanguageGenerationResult

    data object NotConfigured : LanguageGenerationResult

    data class Error(val message: String) : LanguageGenerationResult
}

/**
 * 技能自然语言描述生成器。
 *
 * @param config 已加载的 Provider 配置（baseUrl/model/timeout）。
 * @param apiKey 已解密的 API Key；为 null 或空时视为未配置，直接返回 [LanguageGenerationResult.NotConfigured]。
 * @param connectionFactory 可替换的 HTTP 连接工厂，便于单测注入假连接。
 * @param json 复用项目统一的 JSON 实例，避免重复配置。
 * @param timeoutSeconds 单次生成超时上限，默认 30 秒，覆盖网络等待与模型首 token。
 */
class SkillLanguageGenerator(
    private val config: ProviderConfig,
    private val apiKey: CharArray?,
    private val connectionFactory: (URI) -> HttpURLConnection = { uri ->
        uri.toURL().openConnection() as HttpURLConnection
    },
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {

    /**
     * 根据脚本名和步骤列表生成一段自然语言描述草稿。
     *
     * @param scriptName 脚本名称，会作为上下文喂给模型。
     * @param steps 录制步骤列表；内部只提取动作类型与非敏感目标摘要。
     * @param previousDescription 脚本上已保存的描述；传入后用于计算 [LanguageGenerationResult.Success.changedFromPrevious]。
     * @return 生成结果；不会抛出运行时异常（除协程取消外）。
     */
    suspend fun generate(
        scriptName: String,
        steps: List<RecordedStep>,
        previousDescription: String? = null,
    ): LanguageGenerationResult {
        // 未配置 API Key 时直接返回未配置状态，不发网络请求。
        if (apiKey == null || apiKey.isEmpty()) {
            return LanguageGenerationResult.NotConfigured
        }

        // 构造发送给模型的步骤摘要，确保敏感字段已被过滤。
        val stepSummaries = steps
            .filter { it.enabled }
            .mapIndexed { index, step -> summarizeStep(index + 1, step) }
            .toList()

        val userPrompt = buildString {
            appendLine("脚本名称：${scriptName.ifBlank { "（未命名）" }}")
            appendLine("步骤数：${stepSummaries.size}")
            appendLine("步骤摘要：")
            if (stepSummaries.isEmpty()) {
                appendLine("（暂无启用步骤）")
            } else {
                stepSummaries.joinTo(this, separator = "\n")
            }
            previousDescription?.takeIf(String::isNotBlank)?.let {
                appendLine()
                appendLine("已有描述：")
                appendLine(it.trim())
            }
        }

        val apiKeyString = apiKey.concatToString()
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(timeoutSeconds * 1_000L) {
                    val description = callModel(userPrompt, apiKeyString)
                    val trimmed = description.trim()
                    if (trimmed.isEmpty()) {
                        return@withTimeout LanguageGenerationResult.Error("模型返回了空描述")
                    }
                    val previous = previousDescription?.trim().orEmpty()
                    LanguageGenerationResult.Success(
                        description = trimmed,
                        changedFromPrevious = previous.isEmpty() || trimmed != previous,
                    )
                }
            }
        } catch (cancel: CancellationException) {
            // 协程取消必须原样抛出，避免吞掉结构化并发信号。
            throw cancel
        } catch (error: Exception) {
            val safe = SensitiveLogRedactor.redact(
                message = error.message ?: "语言生成失败",
                apiKeyString,
            )
            LanguageGenerationResult.Error(safe)
        }
        // 注意：apiKeyString 是从 CharArray 复制出来的只读 String，由 JVM 回收；
        // 原始 apiKey CharArray 的清零由调用方在构造/读取时负责，本类不持有长生命周期引用。
    }

    /**
     * 调用 OpenAI 兼容的 /chat/completions 接口，要求模型输出一段中文描述。
     *
     * 该方法与 [dev.aiauto.android.provider.OpenAICompatibleProvider] 保持一致的
     * 请求形态，但 system prompt 针对"写脚本描述"重新定制，且不强制 JSON 输出。
     */
    private fun callModel(userPrompt: String, apiKey: String): String {
        val endpoint = URI.create(
            "${ProviderConfigValidator.normalizeBaseUrl(config.baseUrl)}/chat/completions",
        )
        val request = LanguageChatRequest(
            model = config.model,
            messages = listOf(
                LanguageChatMessage(role = "system", content = SYSTEM_PROMPT),
                LanguageChatMessage(role = "user", content = userPrompt),
            ),
            temperature = 0.3,
            maxTokens = 400,
        )
        val connection = connectionFactory(endpoint)
        val requestBody = json.encodeToString(request).toByteArray(StandardCharsets.UTF_8)
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = config.timeoutSeconds * 1_000
            connection.readTimeout = config.timeoutSeconds * 1_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            try {
                connection.outputStream.use { output -> output.write(requestBody) }
            } finally {
                // 请求体已写入 socket，立即清零，避免 JSON 中的提示词长期驻留内存。
                requestBody.fill(0)
            }

            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readUtf8(MAX_RESPONSE_BYTES) }
                .orEmpty()
            if (status !in 200..299) {
                val safe = SensitiveLogRedactor.redact(
                    parseProviderError(body) ?: "Provider 请求失败，HTTP $status",
                    apiKey,
                )
                throw ProviderHttpException(statusCode = status, message = safe)
            }
            val response = runCatching { json.decodeFromString<LanguageChatResponse>(body) }
                .getOrElse { throw IOException("Provider 返回了无法解析的 JSON", it) }
            return response.choices.firstOrNull()?.message?.content
                ?.takeIf(String::isNotBlank)
                ?: throw IOException("Provider 返回了空 choices")
        } finally {
            connection.disconnect()
        }
    }

    /** 尝试从错误响应体中提取可读的 error.message，失败时返回 null。 */
    private fun parseProviderError(body: String): String? =
        runCatching { json.decodeFromString<ProviderErrorEnvelope>(body).error.message }.getOrNull()

    /**
     * 把单个录制步骤压缩成一行人类可读的摘要，供模型理解技能意图。
     *
     * 敏感处理规则：
     * - `ui.setText` 若携带 `secretRef`，只标注"通过密钥变量输入"，不输出变量名也不输出任何明文。
     * - `ui.setText` 的 `text` 字段属于用户输入内容，即使非敏感也不外发，避免把私聊/验证码等带出去。
     * - `visualTarget`（截图、归一化坐标）完全忽略，不把视觉证据发给模型。
     * - 其他参数（包名、方向、等待超时）只用于动作类型的自然语言描述。
     */
    private fun summarizeStep(index: Int, step: RecordedStep): String {
        val action = step.action
        val targetSummary = summarizeTarget(action.params)
        val actionText = when (action.type) {
            "app.launch" -> "启动应用 ${action.params["packageName"].orParam()}"
            "app.stop" -> "停止应用 ${action.params["packageName"].orParam()}"
            "ui.click" -> "点击 $targetSummary"
            "ui.longClick" -> "长按 $targetSummary"
            "ui.tap" -> "点击屏幕坐标"
            "ui.swipe" -> "在屏幕上滑动"
            "ui.scroll" -> "滚动 $targetSummary"
            "ui.setText" -> describeTextInput(action)
            "ui.back" -> "按下返回键"
            "ui.home" -> "回到桌面"
            "ui.recents" -> "打开最近任务列表"
            "ui.wait" -> "等待界面条件满足"
            "ui.assert" -> "断言界面状态"
            "task.finish" -> "结束任务"
            else -> "执行动作 ${action.type}"
        }
        val disabledHint = if (!step.enabled) "（已禁用）" else ""
        return "$index. $actionText$disabledHint"
    }

    /**
     * 描述文本输入步骤。存在 secretRef 时只说明"通过密钥变量输入"，
     * 不泄露变量名；否则只说"向控件输入文本"，不发送 text 原文。
     */
    private fun describeTextInput(action: RecordedAction): String {
        val hasSecretRef = action.params.containsKey("secretRef")
        val targetSummary = summarizeTarget(action.params)
        return if (hasSecretRef) {
            "向 $targetSummary 输入一段由密钥变量提供的文本（不包含明文）"
        } else {
            "向 $targetSummary 输入文本（内容已脱敏，不发送给模型）"
        }
    }

    /**
     * 从动作参数中提取非敏感的目标标识摘要。
     *
     * target 字段结构由录制层定义，通常包含 packageName、fingerprint、selectorCandidates 等。
     * 这里只挑人类可读且不敏感的字段（包名、resourceId、text、contentDescription），
     * 坐标、bounds、截图哈希等视觉证据一律忽略。
     */
    private fun summarizeTarget(params: JsonObject): String {
        val target = params["target"] as? JsonObject ?: return "目标控件"
        val packageName = target["packageName"].asPlainString()
        val fingerprint = target["fingerprint"] as? JsonObject
        val resourceId = fingerprint?.get("resourceId").asPlainString()
        val selectors = target["selectorCandidates"] as? JsonArray
        val selectorText = selectors
            ?.mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val type = obj["strategy"].asPlainString()
                val value = obj["value"].asPlainString()
                if (type.isBlank() || value.isBlank()) null else "$type=$value"
            }
            ?.filterNot { it.startsWith("text=") && it.length > MAX_SELECTOR_HINT }
            ?.take(2)
            ?.joinToString("；")
            .orEmpty()

        val parts = buildList {
            if (!resourceId.isNullOrBlank()) add("resourceId=$resourceId")
            if (selectorText.isNotBlank()) add(selectorText)
            if (!packageName.isNullOrBlank()) add("包名=$packageName")
        }
        return if (parts.isEmpty()) "目标控件" else "目标控件（${parts.joinToString("；")}）"
    }

    private companion object {
        /** 单次生成的默认超时，与任务要求一致。 */
        const val DEFAULT_TIMEOUT_SECONDS = 30L

        /** 响应体最大字节数，与 Provider 层保持一致，防止异常响应撑爆内存。 */
        const val MAX_RESPONSE_BYTES = 1_048_576

        /** selector 提示截断长度，避免把超长文本选择器原样喂给模型。 */
        const val MAX_SELECTOR_HINT = 60

        /**
         * 系统提示：让模型用简短中文描述技能要做什么，不要复述步骤，不要编造未提供的信息。
         */
        val SYSTEM_PROMPT = """
            你是 Android 自动化脚本的描述助手。
            用户会给你脚本名称和脱敏后的步骤摘要。
            请输出一段 2~4 句的中文自然语言描述，说明这个技能：
            1) 要在哪个 App 上完成什么任务；
            2) 主要操作流程；
            3) 如有文本输入，只说"输入文本"或"输入密钥变量"，不要编造具体内容。
            不要输出 Markdown、不要列步骤编号、不要复述"已有描述"。
        """.trimIndent()
    }
}

/** 把 JsonElement 安全地转成纯字符串，缺失或非字符串时返回空串。 */
private fun JsonElement?.asPlainString(): String =
    (this as? JsonPrimitive)?.contentOrNull.orEmpty()

/** 在动作摘要中安全地读取顶层参数值。 */
private fun JsonElement?.orParam(): String =
    (this as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank).orEmpty().let {
        if (it.isBlank()) "" else "（$it）"
    }

/** 以 UTF-8 读取输入流，超过 maxBytes 时抛出 IO 异常，防止恶意响应撑爆内存。 */
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
            throw IOException("语言生成响应超过 $maxBytes 字节")
        }
        output.write(buffer, 0, read)
    }
}

@Serializable
private data class LanguageChatRequest(
    val model: String,
    val messages: List<LanguageChatMessage>,
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
private data class LanguageChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class LanguageChatResponse(
    val choices: List<LanguageChatChoice> = emptyList(),
)

@Serializable
private data class LanguageChatChoice(
    val message: LanguageChatResponseMessage,
)

@Serializable
private data class LanguageChatResponseMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ProviderErrorEnvelope(
    val error: ProviderErrorBody,
)

@Serializable
private data class ProviderErrorBody(
    val message: String,
)
