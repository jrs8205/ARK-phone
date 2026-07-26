package org.jarsi.arkphone.telecom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.os.ConfigurationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface SpeechEngine {
    fun speak(text: String)
    fun stop()
}

/**
 * The voice the announcement should use: the language the app itself speaks,
 * when the engine actually has it. Null leaves the engine's own default in
 * place — which is what the app relied on until a device whose Google TTS
 * lacked the Finnish voice read Finnish names in another language, turning
 * the announcement into something the user could not make out.
 */
internal fun announcementLocale(
    appLocale: Locale,
    availability: (Locale) -> Int,
): Locale? = appLocale.takeIf { availability(it) >= TextToSpeech.LANG_AVAILABLE }

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
    private var focusRequest: AudioFocusRequest? = null

    override fun speak(text: String) {
        val engine = tts ?: create() ?: return
        takeAudioFocus()
        if (ready) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } else {
            pending = text
        }
    }

    override fun stop() {
        pending = null
        if (ready) tts?.stop()
        releaseAudioFocus()
    }

    /**
     * Ducks whatever else is playing for as long as the announcements run.
     * A WhatsApp call rings from WhatsApp's own player on the same stream —
     * and its ringtone cannot be silenced from here — so without ducking the
     * two mix into something the caller's name is unrecognizable in.
     */
    private fun takeAudioFocus() {
        if (focusRequest != null) return
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(announcementAudioAttributes)
            .build()
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
        }
    }

    private fun releaseAudioFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }

    private val announcementAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun create(): TextToSpeech? = runCatching {
        TextToSpeech(context, ::onInit).also { engine ->
            engine.setAudioAttributes(announcementAudioAttributes)
            tts = engine
        }
    }.getOrNull()

    private fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            applyLanguage()
            pending?.let { speak(it) }
            pending = null
        } else {
            tts = null
            pending = null
        }
    }

    private fun applyLanguage() {
        val engine = tts ?: return
        val appLocale = ConfigurationCompat.getLocales(context.resources.configuration)[0]
            ?: Locale.getDefault()
        val locale = announcementLocale(appLocale) { engine.isLanguageAvailable(it) }
        if (locale != null) engine.language = locale
        Log.i(
            "ArkPhone",
            "Announcement voice: app=$appLocale applied=${locale ?: "engine default"}" +
                " voice=${runCatching { engine.voice?.locale }.getOrNull()}",
        )
    }
}
