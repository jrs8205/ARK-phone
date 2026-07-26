package org.jarsi.arkphone.telecom

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TtsSpeechEngineTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Test
    fun speakingDucksWhateverElseIsPlaying() {
        // A WhatsApp call rings from WhatsApp's own player, and its ringtone
        // and the announcement land on the same stream — without ducking the
        // two mix into something the user cannot make out.
        TtsSpeechEngine(context).speak("Matti soittaa")
        val request = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull(request)
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            request.audioFocusRequest.focusGain,
        )
    }

    @Test
    fun theFocusIsHandedBackWhenSpeakingStops() {
        val engine = TtsSpeechEngine(context)
        engine.speak("Matti soittaa")
        engine.stop()
        assertNotNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun nothingIsHeldBeforeTheFirstAnnouncement() {
        TtsSpeechEngine(context)
        assertNull(shadowOf(audioManager).lastAudioFocusRequest)
    }
}
