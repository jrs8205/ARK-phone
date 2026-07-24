package org.jarsi.arkphone.telecom

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface SpeechEngine {
    fun speak(text: String)
    fun stop()
}

/**
 * Lazily initialized system TTS. The engine initializes asynchronously, so the
 * first requested utterance is parked in [pending] and spoken from onInit; a
 * stop() in between drops it. All calls arrive on the main dispatcher, which
 * onInit also posts to, so the mutable state needs no synchronization.
 */
@Singleton
class TtsSpeechEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechEngine {

    private companion object {
        const val UTTERANCE_ID = "caller_announcement"
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    override fun speak(text: String) {
        val engine = tts ?: create() ?: return
        if (ready) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } else {
            pending = text
        }
    }

    override fun stop() {
        pending = null
        if (ready) tts?.stop()
    }

    private fun create(): TextToSpeech? = runCatching {
        TextToSpeech(context, ::onInit).also { engine ->
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            tts = engine
        }
    }.getOrNull()

    private fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            pending?.let { speak(it) }
            pending = null
        } else {
            tts = null
            pending = null
        }
    }
}
