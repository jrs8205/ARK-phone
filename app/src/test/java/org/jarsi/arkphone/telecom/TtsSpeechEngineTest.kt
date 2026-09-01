package org.jarsi.arkphone.telecom

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * A TTS engine whose binding can die: each queued result is returned by one
 * speak() call in order, and a failing call speaks nothing — exactly how the
 * framework behaves once the engine package updates beneath the process.
 */
@Implements(TextToSpeech::class)
class DeadBindingShadowTextToSpeech : ShadowTextToSpeech() {
    companion object {
        val speakResults = ArrayDeque<Int>()

        /**
         * The framework reports init failure synchronously from inside the
         * constructor when no engine can bind at all (initTts →
         * dispatchOnInit(ERROR) runs the callback inline without an executor).
         */
        var failInitSynchronously = false
    }

    @Implementation
    override fun __constructor__(
        context: Context,
        listener: TextToSpeech.OnInitListener,
        engine: String?,
        packageName: String?,
        useFallback: Boolean,
    ) {
        super.__constructor__(context, listener, engine, packageName, useFallback)
        if (failInitSynchronously) listener.onInit(TextToSpeech.ERROR)
    }

    @Implementation
    override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
        val result = speakResults.removeFirstOrNull() ?: TextToSpeech.SUCCESS
        if (result != TextToSpeech.SUCCESS) return result
        return super.speak(text, queueMode, params, utteranceId)
    }
}

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

    // The engine outlives the TTS package it is bound to: a Speech Services
    // update beneath a long-lived process kills the binding for good, and
    // every speak() after that fails silently — a phone in the field spoke
    // no announcements for days because of it.

    @Test
    @Config(shadows = [DeadBindingShadowTextToSpeech::class])
    fun aDeadEngineBindingIsRebuiltAndTheAnnouncementRetried() {
        DeadBindingShadowTextToSpeech.speakResults.clear()
        DeadBindingShadowTextToSpeech.failInitSynchronously = false
        DeadBindingShadowTextToSpeech.speakResults.add(TextToSpeech.ERROR)

        val engine = TtsSpeechEngine(context)
        engine.speak("Matti soittaa")
        val first = ShadowTextToSpeech.getLastTextToSpeechInstance()
        Shadow.extract<ShadowTextToSpeech>(first).onInitListener.onInit(TextToSpeech.SUCCESS)

        val second = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNotSame(first, second)
        Shadow.extract<ShadowTextToSpeech>(second).onInitListener.onInit(TextToSpeech.SUCCESS)
        assertEquals(
            "Matti soittaa",
            Shadow.extract<ShadowTextToSpeech>(second).lastSpokenText,
        )
    }

    @Test
    @Config(shadows = [DeadBindingShadowTextToSpeech::class])
    fun anEngineThatStillFailsAfterTheRebuildIsLeftAlone() {
        DeadBindingShadowTextToSpeech.speakResults.clear()
        DeadBindingShadowTextToSpeech.failInitSynchronously = false
        DeadBindingShadowTextToSpeech.speakResults.add(TextToSpeech.ERROR)
        DeadBindingShadowTextToSpeech.speakResults.add(TextToSpeech.ERROR)

        val engine = TtsSpeechEngine(context)
        engine.speak("Matti soittaa")
        Shadow.extract<ShadowTextToSpeech>(ShadowTextToSpeech.getLastTextToSpeechInstance())
            .onInitListener.onInit(TextToSpeech.SUCCESS)
        val rebuilt = ShadowTextToSpeech.getLastTextToSpeechInstance()
        Shadow.extract<ShadowTextToSpeech>(rebuilt).onInitListener.onInit(TextToSpeech.SUCCESS)

        // Give up rather than rebuild forever within one announcement...
        assertSame(rebuilt, ShadowTextToSpeech.getLastTextToSpeechInstance())

        // ...but the next announcement gets a fresh try on the engine we kept.
        engine.speak("Liisa soittaa")
        assertEquals(
            "Liisa soittaa",
            Shadow.extract<ShadowTextToSpeech>(rebuilt).lastSpokenText,
        )
    }

    @Test
    @Config(shadows = [DeadBindingShadowTextToSpeech::class])
    fun anEngineWhoseInitFailsInsideTheConstructorIsDroppedAndRetriedLater() {
        DeadBindingShadowTextToSpeech.speakResults.clear()
        DeadBindingShadowTextToSpeech.failInitSynchronously = true

        val engine = TtsSpeechEngine(context)
        engine.speak("Matti soittaa")
        val failed = ShadowTextToSpeech.getLastTextToSpeechInstance()

        // Keeping the dead object would park every later utterance against an
        // engine that never becomes ready; the next announcement must bind a
        // fresh one instead.
        DeadBindingShadowTextToSpeech.failInitSynchronously = false
        engine.speak("Matti soittaa")
        val fresh = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNotSame(failed, fresh)
        Shadow.extract<ShadowTextToSpeech>(fresh).onInitListener.onInit(TextToSpeech.SUCCESS)
        assertEquals(
            "Matti soittaa",
            Shadow.extract<ShadowTextToSpeech>(fresh).lastSpokenText,
        )
    }
}
