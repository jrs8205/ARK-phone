package org.jarsi.arkphone.telecom

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnouncementSpeechRateTest {

    @Test
    fun anExtremeSystemRateIsBroughtBackToSomethingIntelligible() {
        // A device was found reading announcements at 3.57x, which is mush.
        assertEquals(
            AnnouncementSpeech.MAX_RATE,
            announcementSpeechRate(3.57f),
            0.001f,
        )
    }

    @Test
    fun anUnusablySlowRateIsLiftedAsWell() {
        assertEquals(AnnouncementSpeech.MIN_RATE, announcementSpeechRate(0.1f), 0.001f)
    }

    @Test
    fun anOrdinaryPreferenceIsKept() {
        assertEquals(1f, announcementSpeechRate(1f), 0.001f)
        assertEquals(1.2f, announcementSpeechRate(1.2f), 0.001f)
    }

    @Test
    fun aMissingOrNonsenseRateFallsBackToNormal() {
        assertEquals(1f, announcementSpeechRate(0f), 0.001f)
        assertEquals(1f, announcementSpeechRate(-2f), 0.001f)
    }
}
