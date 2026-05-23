package com.woodqc.app.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import java.util.Locale

class FeedbackAudio(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var toneGenerator: ToneGenerator? = null
    private var isTtsInitialized = false

    init {
        // Initialize TextToSpeech
        tts = TextToSpeech(context.applicationContext, this)

        // Initialize ToneGenerator with Music Stream and maximum volume
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {
            // Graceful fallback if Audio subsystem fails initialization
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsInitialized = true
            }
        }
    }

    /**
     * Triggers a clean, high-frequency double beep indicating successful inspection.
     */
    fun playPassBeep() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        } catch (e: Exception) {
            // Ignore device sound generator errors
        }
    }

    /**
     * Triggers a long, low-frequency beep indicating a defect reject.
     */
    fun playRejectBeep() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 400)
        } catch (e: Exception) {
            // Ignore device sound generator errors
        }
    }

    /**
     * Synthesizes spoken vocal instructions for factory workers.
     */
    fun speak(text: String) {
        if (isTtsInitialized) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "QC_FEEDBACK_ID")
        }
    }

    /**
     * Properly tear down references to avoid memory leaks.
     */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        toneGenerator?.release()
        toneGenerator = null
    }
}
