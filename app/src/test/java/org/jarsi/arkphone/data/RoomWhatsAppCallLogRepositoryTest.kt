package org.jarsi.arkphone.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.WhatsAppCallRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomWhatsAppCallLogRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, ArkPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val repository = RoomWhatsAppCallLogRepository(db.whatsAppCallDao())

    @After
    fun tearDown() {
        db.close()
    }

    private fun record(
        name: String? = "Matti Meikäläinen",
        number: String? = "+358 44 5552841",
        type: CallType = CallType.INCOMING,
        timestampMillis: Long = 1_000L,
        durationSeconds: Long = 30L,
        isVideo: Boolean = false,
    ) = WhatsAppCallRecord(name, number, type, timestampMillis, durationSeconds, isVideo)

    @Test
    fun recordedCallComesBackAsAWhatsAppLogEntry() = runTest {
        repository.record(record())
        val entry = repository.calls().first().single()
        assertEquals("Matti Meikäläinen", entry.displayName)
        assertEquals("+358 44 5552841", entry.number)
        assertEquals(CallType.INCOMING, entry.type)
        assertEquals(1_000L, entry.timestampMillis)
        assertEquals(30L, entry.durationSeconds)
        assertEquals(CallSource.WHATSAPP, entry.source)
    }

    @Test
    fun callsAreNewestFirst() = runTest {
        repository.record(record(timestampMillis = 1_000L))
        repository.record(record(timestampMillis = 3_000L))
        repository.record(record(timestampMillis = 2_000L))
        val stamps = repository.calls().first().map { it.timestampMillis }
        assertEquals(listOf(3_000L, 2_000L, 1_000L), stamps)
    }

    @Test
    fun aCallWithoutANumberBecomesAnEntryWithABlankNumber() = runTest {
        repository.record(record(number = null))
        val entry = repository.calls().first().single()
        assertEquals("", entry.number)
        assertEquals("Matti Meikäläinen", entry.displayName)
    }

    @Test
    fun deleteMatchesDifferingNumberFormats() = runTest {
        repository.record(record(number = "+358 44 5552841"))
        repository.record(record(name = "Muu", number = "+358 40 1112223"))
        repository.deleteCallsFor("0445552841")
        val remaining = repository.calls().first()
        assertEquals(listOf("Muu"), remaining.map { it.displayName })
    }

    @Test
    fun nameOnlyRowsAreDeletedByNameWithoutTouchingNumberedRows() = runTest {
        repository.record(record(number = null))
        repository.record(record(number = "+358 44 5552841"))
        repository.deleteCallsForName("Matti Meikäläinen")
        val remaining = repository.calls().first()
        assertEquals(listOf("+358 44 5552841"), remaining.map { it.number })
    }
}
