package dev.aiauto.android.provider

import kotlinx.serialization.json.JsonObject

data class ProviderAction(
    val type: String,
    val params: JsonObject,
)

class ProviderActionParseException(message: String) : IllegalArgumentException(message)
