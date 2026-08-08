package org.jarsi.arkphone.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomArkLinkRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, ArkPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val repository = RoomArkLinkRepository(db.arkLinkDao())

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun aLinkComesBackWithItsNicknameAndKey() = runTest {
        repository.link("+358 44 5552841", "ARK-7K3M-Q2FP", "Jarsi", "pk-test", 1_000L)
        val link = repository.links.first().single()
        assertEquals("445552841", link.numberKey)
        assertEquals("+358 44 5552841", link.number)
        assertEquals("ARK-7K3M-Q2FP", link.code)
        assertEquals("Jarsi", link.nickname)
        assertEquals("pk-test", link.publicKey)
        assertEquals(1_000L, link.linkedAtMillis)
    }

    @Test
    fun relinkingTheSameNumberReplacesTheRowInsteadOfAddingOne() = runTest {
        repository.link("+358 44 5552841", "ARK-7K3M-Q2FP", "Jarsi", "pk-1", 1_000L)
        repository.link("044 555 2841", "ARK-AAAA-BBBB", "Jarsi 2", "pk-2", 2_000L)
        val links = repository.links.first()
        assertEquals(1, links.size)
        assertEquals("ARK-AAAA-BBBB", links.single().code)
    }

    @Test
    fun unlinkingMatchesAnyFormattingOfTheNumber() = runTest {
        repository.link("+358 44 5552841", "ARK-7K3M-Q2FP", "Jarsi", "pk-1", 1_000L)
        repository.unlink("044 555 2841")
        assertEquals(emptyList<Any>(), repository.links.first())
    }

    @Test
    fun unlinkingAnUnknownNumberLeavesTheTableAlone() = runTest {
        repository.link("+358 44 5552841", "ARK-7K3M-Q2FP", "Jarsi", "pk-1", 1_000L)
        repository.unlink("+358 40 1112223")
        assertEquals(1, repository.links.first().size)
        assertNull(repository.links.first().firstOrNull { it.code == "ARK-AAAA-BBBB" })
    }
}
