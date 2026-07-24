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
        enabled: Boolean = true,
        gate: Boolean = true,
        contacts: FakeContactsRepository = FakeContactsRepository(),
    ) = CallerAnnouncer(
        context,
        FakeSettingsRepository(Settings(announceCaller = enabled)),
        contacts,
        speech,
        { gate },
        CoroutineScope(StandardTestDispatcher(testScheduler)),
    )

    @Test
    fun speaksTwiceWhileRinging() = runTest {
        val speech = FakeSpeechEngine()
        announcer(speech).onRinging(ringingCall())
        runCurrent()
        assertEquals(listOf("Alice is calling"), speech.spoken)
        advanceTimeBy(CallerAnnouncer.REPEAT_DELAY_MILLIS)
        runCurrent()
        assertEquals(listOf("Alice is calling", "Alice is calling"), speech.spoken)
    }

    @Test
    fun stopsSpeakingWhenRingingStops() = runTest {
        val speech = FakeSpeechEngine()
        val announcer = announcer(speech)
        announcer.onRinging(ringingCall())
        runCurrent()
        announcer.onRingingStopped("call-1")
        advanceTimeBy(CallerAnnouncer.REPEAT_DELAY_MILLIS)
        runCurrent()
        assertEquals(1, speech.spoken.size)
        assertEquals(1, speech.stops)
    }

    @Test
    fun disabledSettingSpeaksNothing() = runTest {
        val speech = FakeSpeechEngine()
        announcer(speech, enabled = false).onRinging(ringingCall())
        advanceTimeBy(CallerAnnouncer.REPEAT_DELAY_MILLIS)
        runCurrent()
        assertEquals(0, speech.spoken.size)
    }

    @Test
    fun silentOrDndModeSpeaksNothing() = runTest {
        val speech = FakeSpeechEngine()
        announcer(speech, gate = false).onRinging(ringingCall())
        advanceTimeBy(CallerAnnouncer.REPEAT_DELAY_MILLIS)
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
