package dev.aiauto.android.security

data class EncryptedSecret(
    val ciphertext: String,
    val initializationVector: String,
)

interface SecretCipher {
    fun encrypt(plaintext: CharArray): EncryptedSecret

    fun decrypt(secret: EncryptedSecret): CharArray
}
