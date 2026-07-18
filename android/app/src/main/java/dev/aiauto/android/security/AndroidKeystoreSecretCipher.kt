package dev.aiauto.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreSecretCipher(
    private val keyAlias: String = "provider-api-key",
) : SecretCipher {
    override fun encrypt(plaintext: CharArray): EncryptedSecret {
        var bytes: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encodedBytes = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(plaintext))
                .toByteArrayAndClear()
            bytes = encodedBytes
            EncryptedSecret(
                ciphertext = Base64.encodeToString(
                    cipher.doFinal(encodedBytes),
                    Base64.NO_WRAP,
                ),
                initializationVector = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            )
        } finally {
            bytes?.fill(0)
            plaintext.fill('\u0000')
        }
    }

    override fun decrypt(secret: EncryptedSecret): CharArray {
        val iv = Base64.decode(secret.initializationVector, Base64.NO_WRAP)
        var ciphertext: ByteArray? = null
        var plaintext: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val decodedCiphertext = Base64.decode(secret.ciphertext, Base64.NO_WRAP)
            ciphertext = decodedCiphertext
            val decryptedPlaintext = cipher.doFinal(decodedCiphertext)
            plaintext = decryptedPlaintext
            val characters = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decryptedPlaintext))
            characters.toCharArrayAndClear()
        } finally {
            ciphertext?.fill(0)
            plaintext?.fill(0)
            iv.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

private fun ByteBuffer.toByteArrayAndClear(): ByteArray =
    ByteArray(remaining()).also {
        get(it)
        if (hasArray()) {
            array().fill(0)
        }
    }

private fun CharBuffer.toCharArrayAndClear(): CharArray =
    CharArray(remaining()).also {
        get(it)
        if (hasArray()) {
            array().fill('\u0000')
        }
    }
