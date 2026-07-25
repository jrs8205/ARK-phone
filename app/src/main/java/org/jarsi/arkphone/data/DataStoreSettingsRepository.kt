package org.jarsi.arkphone.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
        val ANNOUNCE_WHATSAPP = booleanPreferencesKey("announce_whatsapp")
        val BLOCK_HIDDEN = booleanPreferencesKey("block_hidden_numbers")
        val BLOCK_UNKNOWN = booleanPreferencesKey("block_unknown_callers")
        val BLOCKED_PREFIXES = stringSetPreferencesKey("blocked_prefixes")
        val ALLOW_REPEAT_CALLERS = booleanPreferencesKey("allow_repeat_callers")
        val ALLOWED_NUMBERS = stringSetPreferencesKey("allowed_numbers")
        val ALWAYS_ALLOW_FAVORITES = booleanPreferencesKey("always_allow_favorites")
        val SCHEDULE_ENABLED = booleanPreferencesKey("blocking_schedule_enabled")
        val SCHEDULE_START = intPreferencesKey("blocking_schedule_start_minutes")
        val SCHEDULE_END = intPreferencesKey("blocking_schedule_end_minutes")
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
                announceWhatsApp = preferences[Keys.ANNOUNCE_WHATSAPP] ?: false,
                blockHiddenNumbers = preferences[Keys.BLOCK_HIDDEN] ?: false,
                blockUnknownCallers = preferences[Keys.BLOCK_UNKNOWN] ?: false,
                blockedPrefixes = preferences[Keys.BLOCKED_PREFIXES] ?: emptySet(),
                allowRepeatCallers = preferences[Keys.ALLOW_REPEAT_CALLERS] ?: true,
                allowedNumbers = preferences[Keys.ALLOWED_NUMBERS] ?: emptySet(),
                alwaysAllowFavorites = preferences[Keys.ALWAYS_ALLOW_FAVORITES] ?: true,
                blockingScheduleEnabled = preferences[Keys.SCHEDULE_ENABLED] ?: false,
                blockingScheduleStartMinutes = preferences[Keys.SCHEDULE_START]
                    ?: Settings.DEFAULT_SCHEDULE_START_MINUTES,
                blockingScheduleEndMinutes = preferences[Keys.SCHEDULE_END]
                    ?: Settings.DEFAULT_SCHEDULE_END_MINUTES,
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

    override suspend fun setAnnounceWhatsApp(enabled: Boolean) {
        dataStore.edit { it[Keys.ANNOUNCE_WHATSAPP] = enabled }
    }

    override suspend fun setBlockHiddenNumbers(enabled: Boolean) {
        dataStore.edit { it[Keys.BLOCK_HIDDEN] = enabled }
    }

    override suspend fun setBlockUnknownCallers(enabled: Boolean) {
        dataStore.edit { it[Keys.BLOCK_UNKNOWN] = enabled }
    }

    override suspend fun setBlockedPrefixes(prefixes: Set<String>) {
        dataStore.edit {
            it[Keys.BLOCKED_PREFIXES] = prefixes
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        }
    }

    override suspend fun setAllowRepeatCallers(enabled: Boolean) {
        dataStore.edit { it[Keys.ALLOW_REPEAT_CALLERS] = enabled }
    }

    override suspend fun setAllowedNumbers(numbers: Set<String>) {
        dataStore.edit {
            it[Keys.ALLOWED_NUMBERS] = numbers
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        }
    }

    override suspend fun setAlwaysAllowFavorites(enabled: Boolean) {
        dataStore.edit { it[Keys.ALWAYS_ALLOW_FAVORITES] = enabled }
    }

    override suspend fun setBlockingScheduleEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SCHEDULE_ENABLED] = enabled }
    }

    override suspend fun setBlockingSchedule(startMinutes: Int, endMinutes: Int) {
        dataStore.edit {
            it[Keys.SCHEDULE_START] = startMinutes.coerceIn(0, 24 * 60 - 1)
            it[Keys.SCHEDULE_END] = endMinutes.coerceIn(0, 24 * 60 - 1)
        }
    }
}
