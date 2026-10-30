package com.calum.traininglog

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores one secret (the GitHub token) encrypted with a non-exportable AES-GCM key from the Android Keystore.
 * Only ciphertext + IV are written (to a private SharedPreferences file); the key never leaves the secure hardware/TEE.
 * Nothing here logs, and exceptions are swallowed into null/false so no secret can end up in a message.
 */
object TokenVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "training_log_github_token_v1"
    private const val PREFS = "token_vault"
    private const val K_CT = "ct"
    private const val K_IV = "iv"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.doFinal(plain.toByteArray(Charsets.UTF_8)) to c.iv
    }

    fun decrypt(ciphertext: ByteArray, iv: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Returns false (and stores nothing) if encryption failed. */
    fun save(ctx: Context, token: String): Boolean = try {
        val (ct, iv) = encrypt(token)
        prefs(ctx).edit()
            .putString(K_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString(K_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
        true
    } catch (_: Exception) {
        false
    }

    /** The token, or null if none is stored or it can no longer be decrypted (then the stale entry is dropped). */
    fun load(ctx: Context): String? {
        val p = prefs(ctx)
        val ct = p.getString(K_CT, null) ?: return null
        val iv = p.getString(K_IV, null) ?: return null
        return try {
            decrypt(Base64.decode(ct, Base64.NO_WRAP), Base64.decode(iv, Base64.NO_WRAP))
        } catch (_: Exception) {
            clear(ctx)
            null
        }
    }

    fun has(ctx: Context): Boolean = prefs(ctx).contains(K_CT)

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        } catch (_: Exception) {
        }
    }
}
