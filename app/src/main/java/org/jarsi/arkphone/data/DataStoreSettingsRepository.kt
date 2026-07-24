package org.jarsi.arkphone.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.Settings
import java.io.IOException
import javax.inject.Inject

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private object Keys {
        /** Legacy v1.2 on/off switch; migrated to [ANNOUNCE_MODE] on read. */
        val ANNOUNCE_CALLER = booleanPreferencesKey("announce_caller")
        val ANNOUNCE_MODE = stringPreferencesKey("announce_mode")
        val ANNOUNCE_INTERVAL = intPreferencesKey("announce_interval_seconds")
    }

    override val settings: Flow<Settings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { preferences ->
            val mode = preferences[Keys.ANNOUNCE_MODE]
                ?.let { stored -> AnnounceMode.entries.firstOrNull { it.name == stored } }
                ?: if (preferences[Keys.ANNOUNCE_CALLER] == true) {
                    AnnounceMode.WITH_RINGTONE
                } else {
                    AnnounceMode.OFF
                }
            Settings(
                announceMode = mode,
                announceIntervalSeconds = (
                    preferences[Keys.ANNOUNCE_INTERVAL]
                        ?: Settings.DEFAULT_ANNOUNCE_INTERVAL_SECONDS
                    ).coerceIn(
                    Settings.MIN_ANNOUNCE_INTERVAL_SECONDS,
                    Settings.MAX_ANNOUNCE_INTERVAL_SECONDS,
                ),
            )
        }

    override suspend fun setAnnounceMode(mode: AnnounceMode) {
        dataStore.edit { it[Keys.ANNOUNCE_MODE] = mode.name }
    }

    override suspend fun setAnnounceIntervalSeconds(seconds: Int) {
        dataStore.edit {
            it[Keys.ANNOUNCE_INTERVAL] = seconds.coerceIn(
                Settings.MIN_ANNOUNCE_INTERVAL_SECONDS,
                Settings.MAX_ANNOUNCE_INTERVAL_SECONDS,
            )
        }
    }
}
