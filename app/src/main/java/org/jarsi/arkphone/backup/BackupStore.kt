package org.jarsi.arkphone.backup

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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.BuildConfig
import org.jarsi.arkphone.data.ArkLinkEntity
import org.jarsi.arkphone.data.ArkPhoneDatabase
import org.jarsi.arkphone.data.SimAccountRepository
import org.jarsi.arkphone.data.WhatsAppCallEntity
import org.jarsi.arkphone.util.Clock
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
) {
    @Inject
    constructor(
        dataStore: DataStore<Preferences>,
        database: ArkPhoneDatabase,
        clock: Clock,
        simAccounts: SimAccountRepository,
    ) : this(dataStore, database, clock, BuildConfig.VERSION_NAME, { simAccounts.accounts().map { it.id }.toSet() })

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
     * "not this SIM". The tables go first (one transaction) and the
     * preferences second (one edit); a preferences failure puts the tables
     * back, so the phone is never half old, half new. The whole apply runs
     * non-cancellable: leaving the screen must not cut it in half.
     */
    suspend fun restore(untrusted: BackupSnapshot) {
        val sims = simAccountIds()
        val snapshot = BackupSanitizer.sanitize(untrusted).let { clean ->
            clean.copy(
                preferences = clean.preferences.filterNot { it.key in SIM_KEYS && it.value !in sims },
            )
        }
        withContext(NonCancellable) {
            val previousLinks = database.arkLinkDao().all()
            val previousCalls = database.whatsAppCallDao().callsOnce()
            replaceTables(
                links = snapshot.arkLinks.map {
                    ArkLinkEntity(it.numberKey, it.number, it.code, it.nickname, it.publicKey, it.linkedAtMillis)
                },
                calls = snapshot.whatsAppCalls.map {
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
            try {
                dataStore.edit { prefs ->
                    prefs.clear()
                    snapshot.preferences.forEach { put(prefs, it) }
                }
            } catch (e: Exception) {
                runCatching { replaceTables(previousLinks, previousCalls) }
                throw e
            }
        }
    }

    private suspend fun replaceTables(links: List<ArkLinkEntity>, calls: List<WhatsAppCallEntity>) {
        database.withTransaction {
            val linkDao = database.arkLinkDao()
            linkDao.clear()
            links.forEach { linkDao.upsert(it) }
            val callDao = database.whatsAppCallDao()
            callDao.clear()
            callDao.insertAll(calls)
        }
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
        val DEVICE_ONLY_KEYS = setOf("ark_synced_fcm_token", "ark_synced_fcm_account")

        /** Phone-account ids are per phone; a restored one must exist here. */
        val SIM_KEYS = setOf("call_sim_account_id", "blocking_sim_account_id")
    }
}
