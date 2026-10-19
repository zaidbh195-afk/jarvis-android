package com.technicalbase.jarvis.core

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsPlayer(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("en-IN")
                ready = true
            }
        }
    }
    fun speak(text: String) { if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j") }
    fun shutdown() { tts?.shutdown() }
}
