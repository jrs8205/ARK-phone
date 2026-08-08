package org.jarsi.arkphone.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val DB_NAME = "ark-link-migration-test.db"

private const val V2_TABLE =
    "CREATE TABLE IF NOT EXISTS `whatsapp_calls` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`callerName` TEXT, `callerNumber` TEXT, `type` TEXT NOT NULL, " +
        "`timestampMillis` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, " +
        "`isVideo` INTEGER NOT NULL, " +
        "`sourcePackage` TEXT NOT NULL DEFAULT 'com.whatsapp')"

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArkLinkMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dbFile = context.getDatabasePath(DB_NAME)

    @After
    fun tearDown() {
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    private fun createVersion2Database() {
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(V2_TABLE)
            db.execSQL(
                "INSERT INTO whatsapp_calls " +
                    "(callerName, callerNumber, type, timestampMillis, durationSeconds, " +
                    "isVideo, sourcePackage) " +
                    "VALUES ('Matti', '+358 44 5552841', 'INCOMING', 1000, 30, 0, 'com.whatsapp')",
            )
            db.version = 2
        }
    }

    @Test
    fun migrationKeepsWhatsAppRowsAndAddsAnEmptyLinkTable() = runTest {
        createVersion2Database()
        val db = Room.databaseBuilder(context, ArkPhoneDatabase::class.java, DB_NAME)
            .addMigrations(ArkPhoneDatabase.MIGRATION_1_2, ArkPhoneDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(1, db.whatsAppCallDao().calls().first().size)
            assertTrue(db.arkLinkDao().links().first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun theMigratedDatabaseAcceptsLinkRows() = runTest {
        createVersion2Database()
        val db = Room.databaseBuilder(context, ArkPhoneDatabase::class.java, DB_NAME)
            .addMigrations(ArkPhoneDatabase.MIGRATION_1_2, ArkPhoneDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            db.arkLinkDao().upsert(
                ArkLinkEntity(
                    numberKey = "445552841",
                    number = "+358 44 5552841",
                    code = "ARK-7K3M-Q2FP",
                    nickname = "Jarsi",
                    publicKey = "pk-test",
                    linkedAtMillis = 1_000L,
                ),
            )
            assertEquals("ARK-7K3M-Q2FP", db.arkLinkDao().links().first().single().code)
        } finally {
            db.close()
        }
    }
}
