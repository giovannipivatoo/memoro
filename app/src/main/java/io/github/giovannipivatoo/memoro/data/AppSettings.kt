// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(val petEnabled: Boolean = false, val model: String = "deepseek-flash")

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = state

    fun update(petEnabled: Boolean = state.value.petEnabled, model: String = state.value.model) {
        require(model.isNotBlank())
        prefs.edit().putBoolean("pet_enabled", petEnabled).putString("model", model).apply()
        state.value = AppSettings(petEnabled, model)
    }

    private fun read() = AppSettings(prefs.getBoolean("pet_enabled", false), prefs.getString("model", "deepseek-flash") ?: "deepseek-flash")
}

/** Encrypted API key, kept out of database snapshots and ZIP backups. */
class AiCredentialStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val alias = "memoro-deepseek-key"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun save(apiKey: String) {
        require(apiKey.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        check(prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()) { "Could not save API key" }
    }

    fun read(): String? {
        val iv = prefs.getString("iv", null) ?: return null
        val ciphertext = prefs.getString("ciphertext", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (failure: Exception) {
            throw IllegalStateException("API key unavailable. Remove it in settings and enter it again.", failure)
        }
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "Could not remove API key" }
        keyStore.deleteEntry(alias)
    }

    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
}
