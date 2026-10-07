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
import kotlinx.coroutines.flow.first
import org.jarsi.arkphone.BuildConfig
import org.jarsi.arkphone.data.ArkLinkEntity
import org.jarsi.arkphone.data.ArkPhoneDatabase
import org.jarsi.arkphone.data.WhatsAppCallEntity
import org.jarsi.arkphone.util.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the app owns, in and out of one [BackupSnapshot]. Preferences
 * travel generically (name, type, value) so a setting added later is covered
 * without touching this class.
 */
@Singleton
class BackupStore(
    private val dataStore: DataStore<Preferences>,
    private val database: ArkPhoneDatabase,
    private val clock: Clock,
    private val appVersion: String,
) {
    @Inject
    constructor(dataStore: DataStore<Preferences>, database: ArkPhoneDatabase, clock: Clock) :
        this(dataStore, database, clock, BuildConfig.VERSION_NAME)

    suspend fun snapshot(): BackupSnapshot {
        val preferences = dataStore.data.first().asMap()
            .mapNotNull { (key, value) -> backupPreference(key.name, value) }
            .filter { it.key !in DEVICE_ONLY_KEYS }
        val links = database.arkLinkDao().all().map {
            BackupArkLink(it.numberKey, it.number, it.code, it.nickname, it.publicKey, it.linkedAtMillis)
        }
        val calls = database.whatsAppCallDao().callsOnce().map {
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
     * Replaces the preferences in ONE edit (atomic, and a single emission for
     * every collector) and the two tables in one transaction.
     */
    suspend fun restore(snapshot: BackupSnapshot) {
        dataStore.edit { prefs ->
            prefs.clear()
            snapshot.preferences
                .filter { it.key !in DEVICE_ONLY_KEYS }
                .forEach { put(prefs, it) }
        }
        database.withTransaction {
            val links = database.arkLinkDao()
            links.clear()
            snapshot.arkLinks.forEach {
                links.upsert(ArkLinkEntity(it.numberKey, it.number, it.code, it.nickname, it.publicKey, it.linkedAtMillis))
            }
            val calls = database.whatsAppCallDao()
            calls.clear()
            calls.insertAll(
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
        val DEVICE_ONLY_KEYS = setOf("ark_synced_fcm_token")
    }
}
