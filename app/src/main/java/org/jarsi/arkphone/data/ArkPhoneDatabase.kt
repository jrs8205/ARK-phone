package org.jarsi.arkphone.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
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

@Database(entities = [WhatsAppCallEntity::class], version = 2, exportSchema = false)
abstract class ArkPhoneDatabase : RoomDatabase() {
    abstract fun whatsAppCallDao(): WhatsAppCallDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE whatsapp_calls " +
                        "ADD COLUMN sourcePackage TEXT NOT NULL DEFAULT 'com.whatsapp'",
                )
            }
        }
    }
}
