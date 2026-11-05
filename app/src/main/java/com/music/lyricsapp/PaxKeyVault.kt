package com.music.lyricsapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The optional provider key is encrypted at rest with an Android Keystore key. */
internal object PaxKeyVault {
    private const val PREFS = "lyrics_settings"
    private const val ALIAS = "lyrics_pax_key_v1"
    private const val CIPHERTEXT = "pax_key_ciphertext"
    private const val IV = "pax_key_iv"
    private const val OLD = "pax_key"

    @Synchronized fun read(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val encrypted = prefs.getString(CIPHERTEXT, null)
        if (encrypted != null) {
            val iv = Base64.decode(prefs.getString(IV, null) ?: error("PaxSenix key IV is missing"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }
        val old = prefs.getString(OLD, null) ?: return ""
        write(context, old)
        return old
    }

    @Synchronized fun write(context: Context, value: String) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        if (value.isBlank()) {
            check(prefs.edit().remove(CIPHERTEXT).remove(IV).remove(OLD).commit())
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit()
            .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .remove(OLD).commit()) { "Could not save the encrypted PaxSenix key" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }
}
