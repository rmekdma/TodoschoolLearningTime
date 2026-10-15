package com.example.todoschooltime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(
    val email: String,
    val password: String,
)

class CredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(credentials: Credentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val json = JSONObject().apply {
            put("email", credentials.email)
            put("password", credentials.password)
        }.toString()
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .remove(KEY_ACCOUNT_ID)
            .apply()
    }

    fun load(): Credentials? {
        val ivText = prefs.getString(KEY_IV, null) ?: return null
        val encryptedText = prefs.getString(KEY_CIPHERTEXT, null) ?: return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val encrypted = Base64.decode(encryptedText, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            val email = json.optString("email")
            val password = json.optString("password")
            if (email.isNotBlank() && password.isNotBlank()) Credentials(email, password) else null
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun saveAccountId(accountId: String) {
        if (accountId.isNotBlank()) prefs.edit().putString(KEY_ACCOUNT_ID, accountId).apply()
    }

    fun loadAccountId(): String? = prefs.getString(KEY_ACCOUNT_ID, null)?.takeIf(String::isNotBlank)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "credentials"
        const val KEY_ALIAS = "todoschool_learning_time_credentials"
        const val KEY_IV = "credentials_iv"
        const val KEY_CIPHERTEXT = "credentials_ciphertext"
        const val KEY_ACCOUNT_ID = "credentials_account_id"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
