package com.example.bodycam

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class SpeechManager(
    private val context: Context,
    private val onSosDetected: () -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-PT")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            //Log.d("SPEECH", "Ouviu: $matches")

            val keywords = listOf("socorro","ajuda","mayday","guerra","emergência","ajudem-me","salvem-me")

            val detected = matches?.any { result ->
                keywords.any { keyword ->
                    result.contains(keyword, ignoreCase = true)
                }
            } == true
            if (detected) {
                Log.d("SPEECH", "Pedido de SOCORRO detetado!")
                onSosDetected()
            }

            if (active) restart()
        }

        override fun onError(error: Int) {
            Log.e("SPEECH", "Erro: $error")
            // Reinicia mesmo em caso de erro (silêncio, timeout, etc.)
            if (active) restart()
        }

        override fun onEndOfSpeech() {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onReadyForSpeech(params: Bundle?) {}
    }

    fun start() {
        active = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(listener)
        recognizer?.startListening(intent)
        Log.d("SPEECH", "Escuta iniciada")
    }

    private fun restart() {
        recognizer?.destroy()
        recognizer = null

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (active) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer?.setRecognitionListener(listener)
                recognizer?.startListening(intent)
            }
        }, 300)
    }

    fun stop() {
        active = false
        recognizer?.destroy()
        recognizer = null
        Log.d("SPEECH", "Escuta parada")
    }
}