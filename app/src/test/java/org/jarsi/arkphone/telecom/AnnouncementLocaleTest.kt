package org.jarsi.arkphone.telecom

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AnnouncementLocaleTest {

    private val finnish = Locale.forLanguageTag("fi-FI")

    @Test
    fun theAppLanguageIsUsedWhenTheEngineHasIt() {
        assertEquals(
            finnish,
            announcementLocale(finnish) { TextToSpeech.LANG_COUNTRY_AVAILABLE },
        )
    }

    @Test
    fun aLanguageAvailableWithoutTheCountryStillCounts() {
        assertEquals(finnish, announcementLocale(finnish) { TextToSpeech.LANG_AVAILABLE })
    }

    @Test
    fun missingVoiceDataLeavesTheEngineDefaultAlone() {
        // Speaking Finnish with whatever voice the engine defaulted to is how
        // the announcement turned into mush on a device without the voice.
        assertNull(announcementLocale(finnish) { TextToSpeech.LANG_MISSING_DATA })
    }

    @Test
    fun anUnsupportedLanguageLeavesTheEngineDefaultAlone() {
        assertNull(announcementLocale(finnish) { TextToSpeech.LANG_NOT_SUPPORTED })
    }
}
