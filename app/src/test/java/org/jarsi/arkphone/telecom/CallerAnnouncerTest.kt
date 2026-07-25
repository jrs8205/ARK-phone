package org.jarsi.arkphone.telecom

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallerAnnouncerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private class FakeSpeechEngine : SpeechEngine {
        val spoken = mutableListOf<String>()
        var stops = 0
        override fun speak(text: String) { spoken += text }
        override fun stop() { stops++ }
    }

    private fun ringingCall(name: String? = "Alice", number: String? = "0401234567") =
        CallInfo("call-1", number, name, CallStatus.RINGING, null)

    private fun TestScope.announcer(
        speech: FakeSpeechEngine,
        mode: AnnounceMode = AnnounceMode.WITH_RINGTONE,
        intervalSeconds: Int = 4,
        gate: Boolean = true,
        contacts: FakeContactsRepository = FakeContactsRepository(),
        announceWhatsApp: Boolean = false,
    ) = CallerAnnouncer(
        context,
        FakeSettingsRepository(
            Settings(
                announceMode = mode,
                announceIntervalSeconds = intervalSeconds,
                announceWhatsApp = announceWhatsApp,
            ),
        ),
        contacts,
        speech,
        { gate },
        CoroutineScope(StandardTestDispatcher(testScheduler)),
    )

    @Test
    fun withRingtoneSpeaksExactlyTwice() = runTest {
        val speech = FakeSpeechEngine()
        announcer(speech).onRinging(ringingCall())
        runCurrent()
        assertEquals(listOf("Alice is calling"), speech.spoken)
        advanceTimeBy(CallerAnnouncer.REPEAT_DELAY_MILLIS)
        runCurrent()
        assertEquals(2, speech.spoken.size)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, speech.spoken.size)
    }

    @Test
    fun voiceOnlyRepeatsAtTheConfiguredInterval() = runTest {
        val speech = FakeSpeechEngine()
        val announcer = announcer(speech, mode = AnnounceMode.VOICE_ONLY, intervalSeconds = 5)
        announcer.onRinging(ringingCall())
        runCurrent()
        assertEquals(1, speech.spoken.size)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, speech.spoken.size)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(4, speech.spoken.size)
        // The repeat loop never completes on its own; without this, runTest's
        // final advanceUntilIdle spins the virtual clock forever.
        announcer.onRingingStopped("call-1")
    }

    @Test
    fun voiceOnlyStopsWhenRingingStops() = runTest {
        val speech = FakeSpeechEngine()
        val announcer = announcer(speech, mode = AnnounceMode.VOICE_ONLY, intervalSeconds = 4)
        announcer.onRinging(ringingCall())
        runCurrent()
        announcer.onRingingStopped("call-1")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, speech.spoken.size)
        assertEquals(1, speech.stops)
    }

    @Test
    fun offSpeaksNothing() = runTest {
        val speech = FakeSpeechEngine()
        announcer(speech, mode = AnnounceMode.OFF).onRinging(ringingCall())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, speech.spoken.size)
    }

    @Test
    fun silentOrDndModeSpeaksNothing() = runTest {
        val speech = FakeSpeechEngine()
        announcer(speech, gate = false).onRinging(ringingCall())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, speech.spoken.size)
    }

    @Test
    fun unknownCallerIsAnnouncedAsUnknown() = runTest {
        val speech = FakeSpeechEngine()
        announcer(speech).onRinging(ringingCall(name = null, number = null))
        runCurrent()
        assertEquals(listOf("Unknown caller is calling"), speech.spoken)
    }

    @Test
    fun whatsAppRepeatsAtTheSliderIntervalUntilStopped() = runTest {
        val speech = FakeSpeechEngine()
        val announcer = announcer(speech, mode = AnnounceMode.OFF, intervalSeconds = 6, announceWhatsApp = true)
        announcer.onWhatsAppRinging("wa-key-1", "Jarsi")
        runCurrent()
        assertEquals(listOf("Jarsi is calling on WhatsApp"), speech.spoken)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(2, speech.spoken.size)
        announcer.onWhatsAppRingingStopped("wa-key-1")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, speech.spoken.size)
        assertEquals(1, speech.stops)
    }

    @Test
    fun whatsAppAnnouncementStopsAtTheSafetyWindow() = runTest {
        val speech = FakeSpeechEngine()
        val announcer = announcer(speech, mode = AnnounceMode.OFF, intervalSeconds = 10, announceWhatsApp = true)
        announcer.onWhatsAppRinging("wa-key-1", "Jarsi")
        advanceTimeBy(600_000)
        runCurrent()
        val spokenAfterWindow = speech.spoken.size
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(spokenAfterWindow, speech.spoken.size)
        announcer.onWhatsAppRingingStopped("wa-key-1")
    }

    @Test
    fun whatsAppToggleOffSpeaksNothing() = runTest {
        val speech = FakeSpeechEngine()
        announcer(speech, announceWhatsApp = false)
            .onWhatsAppRinging("wa-key-1", "Jarsi")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, speech.spoken.size)
    }

    @Test
    fun unknownWhatsAppCallerGetsTheUnknownText() = runTest {
        val speech = FakeSpeechEngine()
        val announcer = announcer(speech, announceWhatsApp = true)
        announcer.onWhatsAppRinging("wa-key-1", null)
        runCurrent()
        assertEquals(listOf("Unknown caller is calling on WhatsApp"), speech.spoken)
        announcer.onWhatsAppRingingStopped("wa-key-1")
    }

    @Test
    fun whatsAppCallerNumberIsResolvedFromContacts() = runTest {
        val speech = FakeSpeechEngine()
        val contacts = FakeContactsRepository().apply {
            matchesByNumber["+358 44 5552841"] = ContactMatch("Bob", null)
        }
        val announcer = announcer(speech, contacts = contacts, announceWhatsApp = true)
        announcer.onWhatsAppRinging("wa-key-1", null, "+358 44 5552841")
        runCurrent()
        assertEquals(listOf("Bob is calling on WhatsApp"), speech.spoken)
        announcer.onWhatsAppRingingStopped("wa-key-1")
    }

    @Test
    fun unmatchedWhatsAppCallerNumberIsSpokenAsTheNumber() = runTest {
        val speech = FakeSpeechEngine()
        val announcer = announcer(speech, announceWhatsApp = true)
        announcer.onWhatsAppRinging("wa-key-1", null, "+358 44 5552841")
        runCurrent()
        assertEquals(listOf("+358 44 5552841 is calling on WhatsApp"), speech.spoken)
        announcer.onWhatsAppRingingStopped("wa-key-1")
    }

    @Test
    fun missingDisplayNameIsResolvedFromContacts() = runTest {
        val speech = FakeSpeechEngine()
        val contacts = FakeContactsRepository().apply {
            matchesByNumber["0401234567"] = ContactMatch("Bob", null)
        }
        announcer(speech, contacts = contacts).onRinging(ringingCall(name = null))
        runCurrent()
        assertEquals(listOf("Bob is calling"), speech.spoken)
    }
}
