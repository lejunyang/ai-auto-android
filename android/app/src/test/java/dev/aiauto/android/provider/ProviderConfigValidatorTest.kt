package dev.aiauto.android.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigValidatorTest {
    @Test
    fun `accepts an HTTPS endpoint with a path`() {
        val result = ProviderConfigValidator.validate(
            config = ProviderConfig(
                baseUrl = "https://provider.example.com/openai/v1",
                model = "model-name",
                timeoutSeconds = 45,
            ),
            apiKey = "secret",
        )

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `reports every invalid field`() {
        val result = ProviderConfigValidator.validate(
            config = ProviderConfig(
                baseUrl = "http://user@example.com/v1?token=secret",
                model = " ",
                timeoutSeconds = 301,
            ),
            apiKey = "",
        )

        assertFalse(result.isValid)
        assertEquals(
            setOf(
                ProviderConfigField.BASE_URL,
                ProviderConfigField.MODEL,
                ProviderConfigField.TIMEOUT,
                ProviderConfigField.API_KEY,
            ),
            result.errors.keys,
        )
    }

    @Test
    fun `normalizes surrounding whitespace and trailing slashes`() {
        assertEquals(
            "https://provider.example.com/v1",
            ProviderConfigValidator.normalizeBaseUrl(
                "  https://provider.example.com/v1///  ",
            ),
        )
    }
}
