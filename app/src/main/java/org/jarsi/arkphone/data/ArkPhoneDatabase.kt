package org.jarsi.arkphone.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "whatsapp_calls")
data class WhatsAppCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callerName: String?,
    val callerNumber: String?,
    val type: String,
    val timestampMillis: Long,
    val durationSeconds: Long,
    val isVideo: Boolean,
    @ColumnInfo(defaultValue = "com.whatsapp") val sourcePackage: String = "com.whatsapp",
)

@Dao
interface WhatsAppCallDao {
    @Query("SELECT * FROM whatsapp_calls ORDER BY timestampMillis DESC")
    fun calls(): Flow<List<WhatsAppCallEntity>>

    @Query("SELECT * FROM whatsapp_calls")
    suspend fun callsOnce(): List<WhatsAppCallEntity>

    @Insert
    suspend fun insert(call: WhatsAppCallEntity)

    @Query("DELETE FROM whatsapp_calls WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

/** One device-only link between a phone number and an ARK account. */
@Entity(tableName = "ark_links")
data class ArkLinkEntity(
    @PrimaryKey val numberKey: String,
    val number: String,
    val code: String,
    val nickname: String,
    val publicKey: String,
    val linkedAtMillis: Long,
)

@Dao
interface ArkLinkDao {
    @Query("SELECT * FROM ark_links ORDER BY nickname")
    fun links(): Flow<List<ArkLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: ArkLinkEntity)

    @Query("DELETE FROM ark_links WHERE numberKey = :numberKey")
    suspend fun delete(numberKey: String)
}

@Database(
    entities = [WhatsAppCallEntity::class, ArkLinkEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class ArkPhoneDatabase : RoomDatabase() {
    abstract fun whatsAppCallDao(): WhatsAppCallDao

    abstract fun arkLinkDao(): ArkLinkDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE whatsapp_calls " +
                        "ADD COLUMN sourcePackage TEXT NOT NULL DEFAULT 'com.whatsapp'",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ark_links` (" +
                        "`numberKey` TEXT NOT NULL, " +
                        "`number` TEXT NOT NULL, " +
                        "`code` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, " +
                        "`publicKey` TEXT NOT NULL, " +
                        "`linkedAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`numberKey`))",
                )
            }
        }
    }
}
