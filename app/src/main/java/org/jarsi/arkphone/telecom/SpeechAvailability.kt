package org.jarsi.arkphone.telecom

import android.content.Context
import android.speech.tts.TextToSpeech
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

fun interface SpeechAvailability {
    suspend fun status(): SpeechStatus
}

/**
 * Starts a throwaway engine to find out what the device can do. A phone was
 * found with two engines installed but none selected as the default, where
 * the announcement stayed silent with nothing explaining why.
 */
class AndroidSpeechAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechAvailability {

    override suspend fun status(): SpeechStatus = suspendCancellableCoroutine { continuation ->
        var engine: TextToSpeech? = null
        val created = runCatching {
            TextToSpeech(context) { initStatus ->
                val result = runCatching {
                    if (initStatus != TextToSpeech.SUCCESS) {
                        SpeechStatus.UNAVAILABLE
                    } else {
                        val appLocale = context.resources.configuration.locales[0]
                            ?: Locale.getDefault()
                        val available = engine?.isLanguageAvailable(appLocale)
                            ?: TextToSpeech.LANG_NOT_SUPPORTED
                        if (available >= TextToSpeech.LANG_AVAILABLE) {
                            SpeechStatus.READY
                        } else {
                            SpeechStatus.VOICE_MISSING
                        }
                    }
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
