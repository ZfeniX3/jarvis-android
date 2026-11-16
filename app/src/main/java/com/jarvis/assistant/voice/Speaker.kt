package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.assistant.core.TextUtils
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Respostas faladas em português do Brasil via [TextToSpeech]. */
class Speaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val counter = AtomicInteger()
    private var engine: TextToSpeech? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = complete(utteranceId)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) = complete(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId)

        private fun complete(id: String?) {
            if (id != null) pending.remove(id)?.complete(Unit)
        }
    }

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                ready.complete(false)
            } else {
                val lang = tts.setLanguage(Locale("pt", "BR"))
                val ok = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED
                if (ok) {
                    tts.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts.setOnUtteranceProgressListener(listener)
                }
                ready.complete(ok)
            }
        }
    }

    /**
     * Fala o texto e só retorna quando terminar. Se não houver voz em português instalada,
     * retorna sem falar (a resposta continua visível no painel). Cancelar a coroutine interrompe a fala.
     */
    suspend fun speak(text: String) {
        val clean = TextUtils.forSpeech(text)
        if (clean.isBlank()) return
        if (!ready.await()) return
        val tts = engine ?: return

        val chunks = TextUtils.chunk(clean, MAX_CHUNK)
        var last: CompletableDeferred<Unit>? = null
        try {
            for ((i, chunk) in chunks.withIndex()) {
                val id = "jarvis-${counter.incrementAndGet()}"
                val isLast = i == chunks.lastIndex
                if (isLast) {
                    last = CompletableDeferred<Unit>().also { pending[id] = it }
                }
                val result = tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, id)
                if (result != TextToSpeech.SUCCESS) {
                    pending.remove(id)
                    return
                }
            }
            last?.await()
        } finally {
            if (last?.isCompleted != true) stop()
        }
    }

    fun stop() {
        engine?.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
    }

    private companion object {
        const val MAX_CHUNK = 500
    }
}
