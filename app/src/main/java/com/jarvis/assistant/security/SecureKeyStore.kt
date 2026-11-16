package com.jarvis.assistant.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda a chave da API criptografada.
 *
 * A chave AES-256 fica dentro do Android Keystore (hardware/TEE quando disponível) e nunca sai
 * dele. No armazenamento do app fica apenas o texto cifrado (AES-GCM) da chave da API.
 * A chave da API nunca é escrita em logs.
 */
object SecureKeyStore {

    private const val TAG = "SecureKeyStore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "jarvis_api_key_wrapper_v1"
    private const val PREFS = "jarvis_secure"
    private const val PREF_API_KEY = "api_key_blob"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Salva a chave (criptografada). Retorna false se algo falhar. */
    fun saveApiKey(context: Context, apiKey: String): Boolean {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs(context).edit().putString(PREF_API_KEY, blob).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao salvar a chave", e)
            false
        }
    }

    /** Devolve a chave descriptografada, ou null se não houver (ou se não puder ser lida). */
    fun loadApiKey(context: Context): String? {
        val blob = prefs(context).getString(PREF_API_KEY, null) ?: return null
        return try {
            val parts = blob.split(":")
            if (parts.size != 2) {
                clearApiKey(context)
                null
            } else {
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val data = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(data), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            // Chave do Keystore perdida/invalidada (ex.: restauração de backup): descarta o blob inútil.
            Log.e(TAG, "Não foi possível descriptografar a chave; ela será removida", e)
            clearApiKey(context)
            null
        }
    }

    fun hasApiKey(context: Context): Boolean = !loadApiKey(context).isNullOrBlank()

    fun clearApiKey(context: Context) {
        prefs(context).edit().remove(PREF_API_KEY).apply()
    }

    /** Pré-visualização segura para a tela: "••••abcd". */
    fun maskedKey(context: Context): String {
        val key = loadApiKey(context) ?: return ""
        return "••••" + key.takeLast(4)
    }
}
