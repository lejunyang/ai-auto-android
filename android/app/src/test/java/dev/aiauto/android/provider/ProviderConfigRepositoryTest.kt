package dev.aiauto.android.provider

// 测试用途：验证 ProviderConfigRepository 的功能契约、失败语义及自动化安全边界。

import android.content.SharedPreferences
import java.io.IOException

import dev.aiauto.android.security.EncryptedSecret
import dev.aiauto.android.security.SecretCipher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ProviderConfigRepositoryTest {
    private lateinit var preferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var cipher: RecordingSecretCipher
    private lateinit var repository: ProviderConfigRepository

    @Before
    fun setUp() {
        preferences = mockk()
        editor = mockk()
        cipher = RecordingSecretCipher()
        repository = ProviderConfigRepository(preferences, cipher)

        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns true
    }

    @Test
    fun `loads defaults when no values are stored`() {
        every { preferences.getString(any(), null) } returns null
        every { preferences.getInt(any(), any()) } answers { secondArg() }
        every { preferences.contains(any()) } returns false

        val stored = repository.load()

        assertEquals(ProviderConfig(), stored.config)
        assertFalse(stored.hasApiKey)
    }

    @Test
    fun `loads configured values and reports an encrypted key`() {
        every { preferences.getString("provider.base-url", null) } returns "https://example.com/v1"
        every { preferences.getString("provider.model", null) } returns "custom-model"
        every { preferences.getString("provider.api-key.ciphertext", null) } returns "ciphertext"
        every {
            preferences.getString("provider.api-key.iv", null)
        } returns "initialization-vector"
        every { preferences.getInt("provider.timeout-seconds", any()) } returns 90

        val stored = repository.load()

        assertEquals("https://example.com/v1", stored.config.baseUrl)
        assertEquals("custom-model", stored.config.model)
        assertEquals(90, stored.config.timeoutSeconds)
        assertTrue(stored.hasApiKey)
    }

    @Test
    fun `does not report blank encrypted key material as configured`() {
        every { preferences.getString("provider.base-url", null) } returns null
        every { preferences.getString("provider.model", null) } returns null
        every { preferences.getString("provider.api-key.ciphertext", null) } returns ""
        every {
            preferences.getString("provider.api-key.iv", null)
        } returns "initialization-vector"
        every { preferences.getInt("provider.timeout-seconds", any()) } answers { secondArg() }
        every { preferences.contains("provider.api-key.ciphertext") } returns true
        every { preferences.contains("provider.api-key.iv") } returns true

        assertFalse(repository.load().hasApiKey)
    }

    @Test
    fun `encrypts a new key normalizes fields and clears the caller buffer`() {
        val key = "new-secret".toCharArray()

        repository.save(
            config = ProviderConfig(
                baseUrl = " https://example.com/v1/ ",
                model = " custom-model ",
                timeoutSeconds = 60,
            ),
            apiKey = key,
        )

        assertEquals("new-secret", cipher.lastEncryptedPlaintext)
        assertArrayEquals(CharArray(key.size), key)
        verify { editor.putString("provider.base-url", "https://example.com/v1") }
        verify { editor.putString("provider.model", "custom-model") }
        verify { editor.putInt("provider.timeout-seconds", 60) }
        verify { editor.putString("provider.api-key.ciphertext", "ciphertext") }
        verify { editor.putString("provider.api-key.iv", "initialization-vector") }
        verify(exactly = 1) { editor.commit() }
    }

    @Test
    fun `preserves the encrypted key when no replacement is supplied`() {
        repository.save(
            config = ProviderConfig(),
            apiKey = null,
        )

        assertNull(cipher.lastEncryptedPlaintext)
        verify(exactly = 0) { editor.putString("provider.api-key.ciphertext", any()) }
        verify(exactly = 0) { editor.putString("provider.api-key.iv", any()) }
    }

    @Test
    fun `clears the caller buffer when persistence fails`() {
        val key = "new-secret".toCharArray()
        every { editor.commit() } returns false

        assertThrows(IllegalStateException::class.java) {
            repository.save(ProviderConfig(), key)
        }

        assertArrayEquals(CharArray(key.size), key)
    }

    @Test
    fun `decrypts and clears an encrypted key`() {
        every { preferences.getString("provider.api-key.ciphertext", null) } returns "ciphertext"
        every { preferences.getString("provider.api-key.iv", null) } returns "initialization-vector"

        assertEquals("decrypted-secret", repository.readApiKey()?.concatToString())
        assertEquals(
            EncryptedSecret("ciphertext", "initialization-vector"),
            cipher.lastDecryptedSecret,
        )

        repository.clearApiKey()
        verify { editor.remove("provider.api-key.ciphertext") }
        verify { editor.remove("provider.api-key.iv") }
    }

    @Test
    fun `clears unreadable encrypted key material`() {
        every { preferences.getString("provider.api-key.ciphertext", null) } returns "ciphertext"
        every { preferences.getString("provider.api-key.iv", null) } returns "invalid-vector"
        cipher.decryptFailure = IOException("Keystore unavailable")

        assertNull(repository.readApiKey())
        verify { editor.remove("provider.api-key.ciphertext") }
        verify { editor.remove("provider.api-key.iv") }
        verify(exactly = 1) { editor.commit() }
    }

    private class RecordingSecretCipher : SecretCipher {
        var lastEncryptedPlaintext: String? = null
        var lastDecryptedSecret: EncryptedSecret? = null
        var decryptFailure: Throwable? = null

        override fun encrypt(plaintext: CharArray): EncryptedSecret {
            lastEncryptedPlaintext = plaintext.concatToString()
            return EncryptedSecret(
                ciphertext = "ciphertext",
                initializationVector = "initialization-vector",
            )
        }

        override fun decrypt(secret: EncryptedSecret): CharArray {
            decryptFailure?.let { throw it }
            lastDecryptedSecret = secret
            return "decrypted-secret".toCharArray()
        }
    }
}
