package dev.aiauto.android.provider

// 功能用途：实现 ProviderAction 对应的 AI Provider 配置、调用、动作解析或敏感日志保护。

import kotlinx.serialization.json.JsonObject

data class ProviderAction(
    val type: String,
    val params: JsonObject,
)

class ProviderActionParseException(message: String) : IllegalArgumentException(message)
