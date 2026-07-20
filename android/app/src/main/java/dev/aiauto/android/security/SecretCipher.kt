package dev.aiauto.android.security

/**
 * 功能用途：实现 SecretCipher 对应的 API Key 加密存储与密钥抽象。
 */

data class EncryptedSecret(
    val ciphertext: String,
    val initializationVector: String,
)

interface SecretCipher {
    fun encrypt(plaintext: CharArray): EncryptedSecret

    fun decrypt(secret: EncryptedSecret): CharArray
}
