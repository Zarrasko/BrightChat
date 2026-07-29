package com.gios.lightchat.api

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for the one secret this app holds — the BlueBubbles server
 * password. An AES-256-GCM key lives non-exportable in the AndroidKeyStore
 * (hardware-backed); [encrypt]/[decrypt] use it so the password never sits in
 * plaintext on disk. No third-party crypto dependency, no Play Services.
 */
object SecureStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val AES_ALIAS = "chat_secret_key"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    private fun aesKey(): SecretKey {
        (keyStore.getKey(AES_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Encrypts [plain]; returns base64(iv ‖ ciphertext+tag). */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aesKey()) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64.encodeToString(cipher.iv + ct)
    }

    /** Reverses [encrypt]. */
    fun decrypt(blob: String): String {
        val bytes = b64d.decode(blob)
        val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
        val ct = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
