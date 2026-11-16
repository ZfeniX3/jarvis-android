package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface SpeechResult {
    data class Text(val text: String) : SpeechResult
    data object NoSpeech : SpeechResult
    data object Unavailable : SpeechResult
    data class Failure(val message: String) : SpeechResult
}

/** Reconhecimento de fala em português do Brasil usando o [SpeechRecognizer] oficial do Android. */
class SpeechInput(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun listen(
        onPartial: (String) -> Unit = {},
        onLevel: (Float) -> Unit = {}
    ): SpeechResult = withContext(Dispatchers.Main.immediate) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return@withContext SpeechResult.Unavailable

        withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
            suspendCancellableCoroutine<SpeechResult> { cont ->
                val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                var destroyed = false

                fun destroy() {
                    if (destroyed) return
                    destroyed = true
                    recognizer.setRecognitionListener(null)
                    recognizer.cancel()
                    recognizer.destroy()
                }

                fun finish(result: SpeechResult) {
                    destroy()
                    if (cont.isActive) cont.resume(result)
                }

                cont.invokeOnCancellation { mainHandler.post { destroy() } }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) = finish(mapError(error))

                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        finish(if (text.isEmpty()) SpeechResult.NoSpeech else SpeechResult.Text(text))
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                try {
                    recognizer.startListening(intent)
                } catch (e: Exception) {
                    finish(SpeechResult.Failure("Não consegui iniciar o microfone: ${e.message}"))
                }
            }
        } ?: SpeechResult.NoSpeech
    }

    private fun mapError(error: Int): SpeechResult = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechResult.NoSpeech
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            SpeechResult.Failure("Sem permissão para usar o microfone.")
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            SpeechResult.Failure("O reconhecimento de voz não conseguiu conectar à internet.")
        SpeechRecognizer.ERROR_AUDIO -> SpeechResult.Failure("Erro ao captar o áudio do microfone.")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechResult.Failure("O reconhecedor de voz está ocupado. Tente de novo.")
        12, 13 -> SpeechResult.Failure("O português do Brasil não está disponível no reconhecedor de voz deste aparelho.")
        else -> SpeechResult.Failure("Falha no reconhecimento de voz (código $error).")
    }

    private companion object {
        const val OVERALL_TIMEOUT_MS = 25_000L
    }
}
