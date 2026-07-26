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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val DB_NAME = "migration-test.db"

private const val V1_TABLE =
    "CREATE TABLE IF NOT EXISTS `whatsapp_calls` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`callerName` TEXT, `callerNumber` TEXT, `type` TEXT NOT NULL, " +
        "`timestampMillis` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, " +
        "`isVideo` INTEGER NOT NULL)"

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WhatsAppCallMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dbFile = context.getDatabasePath(DB_NAME)

    @After
    fun tearDown() {
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    /** A database exactly as version 1 of the app left it, with one call. */
    private fun createVersion1Database() {
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(V1_TABLE)
            db.execSQL(
                "INSERT INTO whatsapp_calls " +
                    "(callerName, callerNumber, type, timestampMillis, durationSeconds, isVideo) " +
                    "VALUES ('Matti Meikäläinen', '+358 44 5552841', 'INCOMING', 1000, 30, 0)",
            )
            db.version = 1
        }
    }

    @Test
    fun migrationKeepsOldRowsWithThePersonalPackageDefault() = runTest {
        createVersion1Database()
        val db = Room.databaseBuilder(context, ArkPhoneDatabase::class.java, DB_NAME)
            .addMigrations(ArkPhoneDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val calls = db.whatsAppCallDao().calls().first()
            assertEquals(1, calls.size)
            assertEquals("Matti Meikäläinen", calls.single().callerName)
            assertEquals("com.whatsapp", calls.single().sourcePackage)
        } finally {
            db.close()
        }
    }

    @Test
    fun migratedDatabaseAcceptsNewRowsWithABusinessPackage() = runTest {
        createVersion1Database()
        val db = Room.databaseBuilder(context, ArkPhoneDatabase::class.java, DB_NAME)
            .addMigrations(ArkPhoneDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            db.whatsAppCallDao().insert(
                WhatsAppCallEntity(
                    callerName = "Muu",
                    callerNumber = null,
                    type = "OUTGOING",
                    timestampMillis = 2_000,
                    durationSeconds = 5,
                    isVideo = false,
                    sourcePackage = "com.whatsapp.w4b",
                ),
            )
            val packages = db.whatsAppCallDao().calls().first().map { it.sourcePackage }
            assertEquals(listOf("com.whatsapp.w4b", "com.whatsapp"), packages)
        } finally {
            db.close()
        }
    }
}
