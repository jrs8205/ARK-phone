package org.jarsi.arkphone.telecom

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeWhatsAppCallLogRepository
import org.jarsi.arkphone.util.Clock
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WhatsAppCallMonitorTest {

    private fun TestScope.monitor(
        repository: FakeWhatsAppCallLogRepository,
        contacts: FakeContactsRepository = FakeContactsRepository(),
    ) = WhatsAppCallMonitor(
        repository,
        contacts,
        Clock { testScheduler.currentTime },
        CoroutineScope(StandardTestDispatcher(testScheduler)),
    )

    private fun caller(
        name: String? = "Matti Meikäläinen",
        number: String? = null,
        video: Boolean = false,
    ) = WhatsAppCall(name, number, video)

    @Test
    fun unansweredRingingRecordsAMissedCall() = runTest {
        val repository = FakeWhatsAppCallLogRepository()
        val monitor = monitor(repository)
        monitor.onCallNotificationPosted("ring-1", WhatsAppCallNotificationKind.RINGING, caller())
        advanceTimeBy(8_000)
        monitor.onCallNotificationRemoved("ring-1")
        advanceTimeBy(WhatsAppCallMonitor.ANSWER_GRACE_MILLIS + 1)
        runCurrent()
        val record = repository.recorded.single()
        assertEquals(CallType.MISSED, record.type)
        assertEquals("Matti Meikäläinen", record.callerName)
        assertEquals(0L, record.timestampMillis)
        assertEquals(0L, record.durationSeconds)
    }

    @Test
    fun answeredCallRecordsIncomingWithDuration() = runTest {
        val repository = FakeWhatsAppCallLogRepository()
        val monitor = monitor(repository)
        monitor.onCallNotificationPosted("ring-1", WhatsAppCallNotificationKind.RINGING, caller())
        advanceTimeBy(4_000)
        monitor.onCallNotificationRemoved("ring-1")
        advanceTimeBy(1_000)
        monitor.onCallNotificationPosted("call-1", WhatsAppCallNotificationKind.ONGOING, caller(name = null))
        advanceTimeBy(65_000)
        monitor.onCallNotificationRemoved("call-1")
        runCurrent()
        val record = repository.recorded.single()
        assertEquals(CallType.INCOMING, record.type)
        assertEquals(65L, record.durationSeconds)
        assertEquals(0L, record.timestampMillis)
    }

    @Test
    fun ongoingBeforeRingingRemovalStillCountsAsAnswered() = runTest {
        val repository = FakeWhatsAppCallLogRepository()
        val monitor = monitor(repository)
        monitor.onCallNotificationPosted("ring-1", WhatsAppCallNotificationKind.RINGING, caller())
        advanceTimeBy(4_000)
        monitor.onCallNotificationPosted("call-1", WhatsAppCallNotificationKind.ONGOING, caller(name = null))
        advanceTimeBy(500)
        monitor.onCallNotificationRemoved("ring-1")
        advanceTimeBy(30_000)
        monitor.onCallNotificationRemoved("call-1")
        advanceTimeBy(WhatsAppCallMonitor.ANSWER_GRACE_MILLIS + 1)
        runCurrent()
        val record = repository.recorded.single()
        assertEquals(CallType.INCOMING, record.type)
        assertEquals("Matti Meikäläinen", record.callerName)
    }

    @Test
    fun ongoingAloneRecordsAnOutgoingCall() = runTest {
        val repository = FakeWhatsAppCallLogRepository()
        val monitor = monitor(repository)
        monitor.onCallNotificationPosted("call-1", WhatsAppCallNotificationKind.ONGOING, caller())
        advanceTimeBy(30_000)
        monitor.onCallNotificationRemoved("call-1")
        runCurrent()
        val record = repository.recorded.single()
        assertEquals(CallType.OUTGOING, record.type)
        assertEquals(30L, record.durationSeconds)
        assertEquals(0L, record.timestampMillis)
    }

    @Test
    fun ongoingUpdateDoesNotRestartTheCall() = runTest {
        val repository = FakeWhatsAppCallLogRepository()
        val monitor = monitor(repository)
        monitor.onCallNotificationPosted("call-1", WhatsAppCallNotificationKind.ONGOING, caller())
        advanceTimeBy(10_000)
        monitor.onCallNotificationPosted("call-1", WhatsAppCallNotificationKind.ONGOING, caller())
        advanceTimeBy(10_000)
        monitor.onCallNotificationRemoved("call-1")
        runCurrent()
        assertEquals(20L, repository.recorded.single().durationSeconds)
    }

    @Test
    fun ringingCallerIsPreferredForAnsweredCalls() = runTest {
        val repository = FakeWhatsAppCallLogRepository()
        val monitor = monitor(repository)
        monitor.onCallNotificationPosted(
            "ring-1",
            WhatsAppCallNotificationKind.RINGING,
            caller(name = null, number = "+358 44 5552841"),
        )
        monitor.onCallNotificationPosted("call-1", WhatsAppCallNotificationKind.ONGOING, caller(name = null))
        advanceTimeBy(10_000)
        monitor.onCallNotificationRemoved("call-1")
        runCurrent()
        assertEquals("+358 44 5552841", repository.recorded.single().callerNumber)
    }

    @Test
    fun staleRingingDoesNotTurnAnOngoingIntoIncoming() = runTest {
        val repository = FakeWhatsAppCallLogRepository()
        val monitor = monitor(repository)
        monitor.onCallNotificationPosted("ring-1", WhatsAppCallNotificationKind.RINGING, caller())
        advanceTimeBy(WhatsAppCallMonitor.STALE_RINGING_MILLIS + 1)
        monitor.onCallNotificationPosted("call-1", WhatsAppCallNotificationKind.ONGOING, caller(name = "Muu"))
        advanceTimeBy(10_000)
        monitor.onCallNotificationRemoved("call-1")
        runCurrent()
        val record = repository.recorded.single()
        assertEquals(CallType.OUTGOING, record.type)
        assertEquals("Muu", record.callerName)
    }

    @Test
    fun nameOnlyCallerGetsItsNumberFromContacts() = runTest {
        val repository = FakeWhatsAppCallLogRepository()
        val contacts = FakeContactsRepository().apply {
            allContacts.value = listOf(
                Contact(1, "Matti Meikäläinen", "+358 44 5552841", null, false),
            )
        }
        val monitor = monitor(repository, contacts)
        monitor.onCallNotificationPosted("call-1", WhatsAppCallNotificationKind.ONGOING, caller())
        advanceTimeBy(10_000)
        monitor.onCallNotificationRemoved("call-1")
        runCurrent()
        val record = repository.recorded.single()
        assertEquals("Matti Meikäläinen", record.callerName)
        assertEquals("+358 44 5552841", record.callerNumber)
    }

    @Test
    fun videoFlagIsRecorded() = runTest {
        val repository = FakeWhatsAppCallLogRepository()
        val monitor = monitor(repository)
        monitor.onCallNotificationPosted("call-1", WhatsAppCallNotificationKind.ONGOING, caller(video = true))
        advanceTimeBy(10_000)
        monitor.onCallNotificationRemoved("call-1")
        runCurrent()
        assertEquals(true, repository.recorded.single().isVideo)
    }
}
