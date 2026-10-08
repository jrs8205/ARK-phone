package org.jarsi.arkphone.backup

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreJournalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun anAbsentJournalCanBeReadAndDeleted() {
        val journal = RestoreJournal(tmp.root)
        assertNull(journal.read())
        journal.delete()
        assertFalse(journal.exists())
    }

    @Test
    fun aJournalCanBeReadBackAndRemoved() {
        val journal = RestoreJournal(tmp.root)
        val snapshot = BackupSnapshot(1L, "1.28", emptyList(), emptyList(), emptyList())
        journal.write("pending", snapshot)
        assertEquals(RestoreJournal.Entry("pending", snapshot), journal.read())
        journal.delete()
        assertFalse(journal.exists())
    }

    @Test
    fun anUnreadableJournalIsNotTreatedAsAbsent() {
        val path = File(tmp.root, "restore.journal").also { it.mkdir() }
        val journal = RestoreJournal(tmp.root)
        assertThrows(IOException::class.java) { journal.read() }
        assertTrue(path.exists())
    }

    @Test
    fun aMalformedJournalIsNotTreatedAsAbsent() {
        val path = File(tmp.root, "restore.journal").also { it.writeText("incomplete") }
        val journal = RestoreJournal(tmp.root)
        assertThrows(IOException::class.java) { journal.read() }
        assertEquals("incomplete", path.readText())
    }

    @Test
    fun aFailedDeletionIsReported() {
        val path = File(tmp.root, "restore.journal").also { it.mkdir() }
        File(path, "entry").writeText("pending")
        val journal = RestoreJournal(tmp.root)
        assertThrows(IOException::class.java) { journal.delete() }
        assertTrue(journal.exists())
    }
}
