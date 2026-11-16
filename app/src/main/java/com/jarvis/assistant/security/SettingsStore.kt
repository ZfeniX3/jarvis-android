package com.jarvis.assistant.security

import android.content.Context

/** Preferências não sensíveis (modelo, voz, posição da bolinha). A chave da API fica em [SecureKeyStore]. */
class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var speakResponses: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEAK, value).apply()

    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()

    companion object {
        // Identificadores de modelo da API do Claude
        const val MODEL_FAST = "claude-haiku-5-5"
        const val MODEL_BALANCED = "claude-sonnet-5-5"
        const val MODEL_ADVANCED = "claude-opus-5-5"
        const val DEFAULT_MODEL = MODEL_BALANCED

        private const val KEY_MODEL = "model"
        private const val KEY_SPEAK = "speak_responses"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
    }
}
