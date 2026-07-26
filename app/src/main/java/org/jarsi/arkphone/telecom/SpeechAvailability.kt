package org.jarsi.arkphone.telecom

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume

/** What the device can actually do about speaking a caller's name. */
enum class SpeechStatus {
    /** A speech engine is there and has the app's language. */
    READY,

    /** The engine works but does not have the app's language installed. */
    VOICE_MISSING,

    /** No usable engine: none installed, none chosen, or it failed to start. */
    UNAVAILABLE,
}

/**
 * An engine that reports no voice at all cannot speak, whatever it says
 * about languages — a phone with two engines installed but none chosen as
 * the default behaved exactly like that, and calling it a missing language
 * sent the user off to install a voice for an engine that would never run.
 */
internal fun speechStatusFrom(
    initialized: Boolean,
    hasVoice: Boolean,
    languageAvailability: Int,
): SpeechStatus = when {
    !initialized || !hasVoice -> SpeechStatus.UNAVAILABLE
    languageAvailability >= TextToSpeech.LANG_AVAILABLE -> SpeechStatus.READY
    else -> SpeechStatus.VOICE_MISSING
}

fun interface SpeechAvailability {
    suspend fun status(): SpeechStatus
}

/** Starts a throwaway engine to find out what the device can do. */
class AndroidSpeechAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechAvailability {

    override suspend fun status(): SpeechStatus = suspendCancellableCoroutine { continuation ->
        var engine: TextToSpeech? = null
        val created = runCatching {
            TextToSpeech(context) { initStatus ->
                val result = runCatching {
                    val appLocale = context.resources.configuration.locales[0]
                        ?: Locale.getDefault()
                    val status = speechStatusFrom(
                        initialized = initStatus == TextToSpeech.SUCCESS,
                        hasVoice = runCatching { engine?.voice }.getOrNull() != null,
                        languageAvailability = runCatching { engine?.isLanguageAvailable(appLocale) }
                            .getOrNull() ?: TextToSpeech.LANG_NOT_SUPPORTED,
                    )
                    Log.i(
                        "ArkPhone",
                        "Speech availability: $status engine=${runCatching { engine?.defaultEngine }.getOrNull()}" +
                            " voice=${runCatching { engine?.voice?.name }.getOrNull()} locale=$appLocale",
                    )
                    status
                }.getOrDefault(SpeechStatus.UNAVAILABLE)
                runCatching { engine?.shutdown() }
                if (continuation.isActive) continuation.resume(result)
            }
        }.getOrNull()
        engine = created
        if (created == null && continuation.isActive) {
            continuation.resume(SpeechStatus.UNAVAILABLE)
        }
        continuation.invokeOnCancellation { runCatching { created?.shutdown() } }
    }
}
