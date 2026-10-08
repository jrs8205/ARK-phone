package org.jarsi.arkphone.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.BuildConfig
import org.jarsi.arkphone.data.ArkLinkEntity
import org.jarsi.arkphone.data.ArkPhoneDatabase
import org.jarsi.arkphone.data.SimAccountRepository
import org.jarsi.arkphone.data.WhatsAppCallEntity
import org.jarsi.arkphone.util.Clock
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the app owns, in and out of one [BackupSnapshot]. Preferences
 * travel generically (name, type, value); [BackupSanitizer] decides on the
 * way back in what the app could have written itself.
 */
@Singleton
class BackupStore(
    private val dataStore: DataStore<Preferences>,
    private val database: ArkPhoneDatabase,
    private val clock: Clock,
    private val appVersion: String,
    private val simAccountIds: suspend () -> Set<String>,
    journalDir: File,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        dataStore: DataStore<Preferences>,
        database: ArkPhoneDatabase,
        clock: Clock,
        simAccounts: SimAccountRepository,
    ) : this(
        dataStore, database, clock, BuildConfig.VERSION_NAME,
        { simAccounts.accounts().map { it.id }.toSet() },
        context.noBackupFilesDir,
    )

    private val journal = RestoreJournal(journalDir)

    /**
     * Finishes a restore an earlier process did not get to finish: one whose
     * preferences committed (they carry its id) but whose tables did not.
     * Called at process start; a journal whose preferences never committed
     * is simply discarded, since SQLite has already rolled its tables back.
     */
    suspend fun recoverInterruptedRestore() {
        val entry = journal.read()
        if (entry == null) {
            journal.delete()
            return
        }
        withContext(NonCancellable) {
            if (dataStore.data.first()[RESTORE_ID_KEY] == entry.id) {
                database.withTransaction { replaceTables(entry.snapshot) }
            }
            journal.delete()
        }
    }

    suspend fun snapshot(): BackupSnapshot {
        val preferences = dataStore.data.first().asMap()
            .mapNotNull { (key, value) -> backupPreference(key.name, value) }
            .filter { it.key !in DEVICE_ONLY_KEYS }
        val links = database.arkLinkDao().all().map {
            BackupArkLink(it.numberKey, it.number, it.code, it.nickname, it.publicKey, it.linkedAtMillis)
        }
        // Bounded like the import side: a file this version cannot read back
        // must never be what "Backup saved" refers to.
        val calls = database.whatsAppCallDao().newest(BackupSanitizer.MAX_CALLS).map {
            BackupWhatsAppCall(
                callerName = it.callerName,
                callerNumber = it.callerNumber,
                type = it.type,
                timestampMillis = it.timestampMillis,
                durationSeconds = it.durationSeconds,
                isVideo = it.isVideo,
                sourcePackage = it.sourcePackage,
            )
        }
        return BackupSnapshot(clock.nowMillis(), appVersion, preferences, links, calls)
    }

    /**
     * Applies a file. The file is untrusted ([BackupSanitizer]); SIM
     * restrictions naming an account this phone does not have are dropped,
     * since a stale `blocking_sim_account_id` would make every rule read as
     * "not this SIM".
     *
     * One SQLite transaction spans both stores: the preferences are written
     * (one edit) while the table replacement is still uncommitted, so a
     * failed preferences write is rolled back by SQLite itself — there is
     * no compensating write that could fail and lose the only copy of the
     * original rows — and the write lock keeps WhatsAppCallMonitor and
     * the link screen out until the outcome is known, so a call recorded
     * meanwhile lands on whichever tables survive.
     *
     * What remains is the COMMIT of the already written transaction after
     * the preferences file has been renamed into place (disk full there, or
     * a process death): new preferences next to old tables. For that the
     * snapshot is journalled before anything changes and the preferences
     * carry the restore's id: a failed commit is retried at once, and a
     * retry that fails too leaves the journal for the next process start
     * ([recoverInterruptedRestore]). The whole apply runs non-cancellable:
     * leaving the screen must not cut it in half.
     */
    suspend fun restore(untrusted: BackupSnapshot) {
        val sims = simAccountIds()
        val snapshot = BackupSanitizer.sanitize(untrusted).let { clean ->
            clean.copy(
                preferences = clean.preferences.filterNot { it.key in SIM_KEYS && it.value !in sims },
            )
        }
        withContext(NonCancellable) {
            val restoreId = UUID.randomUUID().toString()
            journal.write(restoreId, snapshot)
            var preferencesCommitted = false
            try {
                database.withTransaction {
                    replaceTables(snapshot)
                    dataStore.edit { prefs ->
                        prefs.clear()
                        snapshot.preferences.forEach { put(prefs, it) }
                        prefs[RESTORE_ID_KEY] = restoreId
                    }
                    preferencesCommitted = true
                }
            } catch (e: Exception) {
                if (!preferencesCommitted) {
                    // SQLite rolled the tables back and the preferences are
                    // as they were: nothing is left for anyone to finish.
                    journal.delete()
                    throw e
                }
                // The preferences are in, the tables are not. Finish now if
                // the database lets us; otherwise the journal stays and
                // the next start finishes it.
                try {
                    database.withTransaction { replaceTables(snapshot) }
                } catch (retry: Exception) {
                    e.addSuppressed(retry)
                    throw e
                }
            }
            journal.delete()
        }
    }

    private suspend fun replaceTables(snapshot: BackupSnapshot) {
        val linkDao = database.arkLinkDao()
        linkDao.clear()
        snapshot.arkLinks.forEach {
            linkDao.upsert(ArkLinkEntity(it.numberKey, it.number, it.code, it.nickname, it.publicKey, it.linkedAtMillis))
        }
        val callDao = database.whatsAppCallDao()
        callDao.clear()
        callDao.insertAll(
            snapshot.whatsAppCalls.map {
                WhatsAppCallEntity(
                    callerName = it.callerName,
                    callerNumber = it.callerNumber,
                    type = it.type,
                    timestampMillis = it.timestampMillis,
                    durationSeconds = it.durationSeconds,
                    isVideo = it.isVideo,
                    sourcePackage = it.sourcePackage,
                )
            },
        )
    }

    private fun backupPreference(name: String, value: Any): BackupPreference? = when (value) {
        is Boolean -> BackupPreference(name, BackupPreference.Type.BOOLEAN, value)
        is Int -> BackupPreference(name, BackupPreference.Type.INT, value)
        is Long -> BackupPreference(name, BackupPreference.Type.LONG, value)
        is Float -> BackupPreference(name, BackupPreference.Type.FLOAT, value)
        is Double -> BackupPreference(name, BackupPreference.Type.DOUBLE, value)
        is String -> BackupPreference(name, BackupPreference.Type.STRING, value)
        is Set<*> -> BackupPreference(name, BackupPreference.Type.STRING_SET, value.map { it as String }.toSet())
        else -> null
    }

    private fun put(prefs: MutablePreferences, preference: BackupPreference) {
        val name = preference.key
        when (preference.type) {
            BackupPreference.Type.BOOLEAN -> prefs[booleanPreferencesKey(name)] = preference.value as Boolean
            BackupPreference.Type.INT -> prefs[intPreferencesKey(name)] = preference.value as Int
            BackupPreference.Type.LONG -> prefs[longPreferencesKey(name)] = preference.value as Long
            BackupPreference.Type.FLOAT -> prefs[floatPreferencesKey(name)] = preference.value as Float
            BackupPreference.Type.DOUBLE -> prefs[doublePreferencesKey(name)] = preference.value as Double
            BackupPreference.Type.STRING -> prefs[stringPreferencesKey(name)] = preference.value as String
            BackupPreference.Type.STRING_SET -> {
                @Suppress("UNCHECKED_CAST")
                prefs[stringSetPreferencesKey(name)] = preference.value as Set<String>
            }
        }
    }

    companion object {
        /**
         * This phone's push registration. Restoring another phone's token
         * would keep the worker waking that phone instead of this one.
         */
        val DEVICE_ONLY_KEYS = setOf("ark_synced_fcm_token", "ark_synced_fcm_account", "backup_restore_id")

        /** Written with the restored preferences; tells a journal whether they committed. */
        private val RESTORE_ID_KEY = stringPreferencesKey("backup_restore_id")

        /** Phone-account ids are per phone; a restored one must exist here. */
        val SIM_KEYS = setOf("call_sim_account_id", "blocking_sim_account_id")
    }
}
