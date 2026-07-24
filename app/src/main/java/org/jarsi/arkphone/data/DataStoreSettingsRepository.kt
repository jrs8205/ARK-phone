package org.jarsi.arkphone.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.jarsi.arkphone.data.model.Settings
import java.io.IOException
import javax.inject.Inject

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private object Keys {
        val ANNOUNCE_CALLER = booleanPreferencesKey("announce_caller")
    }

    override val settings: Flow<Settings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { preferences ->
            Settings(
                announceCaller = preferences[Keys.ANNOUNCE_CALLER] ?: false,
            )
        }

    override suspend fun setAnnounceCaller(enabled: Boolean) {
        dataStore.edit { it[Keys.ANNOUNCE_CALLER] = enabled }
    }
}
