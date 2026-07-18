package dev.aiauto.android.provider

import android.content.SharedPreferences
import java.io.IOException
import java.security.GeneralSecurityException

import dev.aiauto.android.security.EncryptedSecret
import dev.aiauto.android.security.SecretCipher

data class StoredProviderConfig(
    val config: ProviderConfig,
    val hasApiKey: Boolean,
)

class ProviderConfigRepository(
    private val preferences: SharedPreferences,
    private val secretCipher: SecretCipher,
) {
    fun load(): StoredProviderConfig = StoredProviderConfig(
        config = ProviderConfig(
            baseUrl = preferences.getString(KEY_BASE_URL, null)
                ?: ProviderConfig().baseUrl,
            model = preferences.getString(KEY_MODEL, null)
                ?: ProviderConfig().model,
            timeoutSeconds = preferences.getInt(
                KEY_TIMEOUT_SECONDS,
                ProviderConfig().timeoutSeconds,
            ),
        ),
        hasApiKey = loadEncryptedApiKey() != null,
    )

    fun save(config: ProviderConfig, apiKey: CharArray?) {
        try {
            val editor = preferences.edit()
                .putString(KEY_BASE_URL, ProviderConfigValidator.normalizeBaseUrl(config.baseUrl))
                .putString(KEY_MODEL, config.model.trim())
                .putInt(KEY_TIMEOUT_SECONDS, config.timeoutSeconds)

            if (apiKey != null && apiKey.isNotEmpty()) {
                val encrypted = secretCipher.encrypt(apiKey)
                editor
                    .putString(KEY_API_KEY_CIPHERTEXT, encrypted.ciphertext)
                    .putString(KEY_API_KEY_IV, encrypted.initializationVector)
            }
            check(editor.commit()) { "Failed to persist provider configuration" }
        } finally {
            apiKey?.fill('\u0000')
        }
    }

    fun readApiKey(): CharArray? {
        val encrypted = loadEncryptedApiKey() ?: return null
        return try {
            secretCipher.decrypt(encrypted)
        } catch (_: IOException) {
            clearApiKey()
            null
        } catch (_: GeneralSecurityException) {
            clearApiKey()
            null
        } catch (_: IllegalArgumentException) {
            clearApiKey()
            null
        }
    }

    fun clearApiKey() {
        check(
            preferences.edit()
                .remove(KEY_API_KEY_CIPHERTEXT)
                .remove(KEY_API_KEY_IV)
                .commit(),
        ) { "Failed to clear provider API key" }
    }

    private fun loadEncryptedApiKey(): EncryptedSecret? {
        val ciphertext = preferences.getString(KEY_API_KEY_CIPHERTEXT, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val initializationVector = preferences.getString(KEY_API_KEY_IV, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return EncryptedSecret(
            ciphertext = ciphertext,
            initializationVector = initializationVector,
        )
    }

    private companion object {
        const val KEY_BASE_URL = "provider.base-url"
        const val KEY_MODEL = "provider.model"
        const val KEY_TIMEOUT_SECONDS = "provider.timeout-seconds"
        const val KEY_API_KEY_CIPHERTEXT = "provider.api-key.ciphertext"
        const val KEY_API_KEY_IV = "provider.api-key.iv"
    }
}
