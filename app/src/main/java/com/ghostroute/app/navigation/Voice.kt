package com.ghostroute.app.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Thin wrapper over Android's on-device [TextToSpeech] for spoken navigation
 * prompts. Fully offline — it uses whatever system TTS engine is installed and
 * never touches the network or Google Play Services (works on GrapheneOS as long
 * as a TTS engine is present; degrades silently if none is).
 */
class Voice(context: Context) {

    @Volatile
    private var ready = false
    var enabled = true

    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
        }
    }.apply {
        runCatching { language = Locale.US }
    }

    fun speak(text: String) {
        if (!enabled || !ready) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun stop() {
        runCatching { tts.stop() }
    }

    fun shutdown() {
        runCatching {
            tts.stop()
            tts.shutdown()
        }
    }
}
