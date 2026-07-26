package org.jarsi.arkphone.telecom

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechStatusTest {

    @Test
    fun anEngineWithTheLanguageIsReady() {
        assertEquals(
            SpeechStatus.READY,
            speechStatusFrom(
                initialized = true,
                hasVoice = true,
                languageAvailability = TextToSpeech.LANG_COUNTRY_AVAILABLE,
            ),
        )
    }

    @Test
    fun anEngineWithoutTheLanguageOnlyMissesTheVoice() {
        assertEquals(
            SpeechStatus.VOICE_MISSING,
            speechStatusFrom(
                initialized = true,
                hasVoice = true,
                languageAvailability = TextToSpeech.LANG_MISSING_DATA,
            ),
        )
    }

    @Test
    fun anEngineThatHasNoVoiceAtAllCannotSpeak() {
        // A phone was found with engines installed but none chosen as the
        // default: it initialized, yet had no voice and said nothing. Calling
        // that a missing language sent the user to install a voice for an
        // engine that was never going to be used.
        assertEquals(
            SpeechStatus.UNAVAILABLE,
            speechStatusFrom(
                initialized = true,
                hasVoice = false,
                languageAvailability = TextToSpeech.LANG_MISSING_DATA,
            ),
        )
    }

    @Test
    fun anEngineThatFailsToStartCannotSpeak() {
        assertEquals(
            SpeechStatus.UNAVAILABLE,
            speechStatusFrom(
                initialized = false,
                hasVoice = true,
                languageAvailability = TextToSpeech.LANG_AVAILABLE,
            ),
        )
    }
}
