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
import org.jarsi.arkphone.data.model.BlockedCallAction
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
        val BLOCK_ALL = booleanPreferencesKey("block_all_callers")
        val BLOCK_HIDDEN = booleanPreferencesKey("block_hidden_numbers")
        val BLOCK_UNKNOWN = booleanPreferencesKey("block_unknown_callers")
        val BLOCKED_PREFIXES = stringSetPreferencesKey("blocked_prefixes")
        val ALLOW_REPEAT_CALLERS = booleanPreferencesKey("allow_repeat_callers")
        val REPEAT_WINDOW = intPreferencesKey("repeat_caller_window_minutes")
        val ALLOWED_NUMBERS = stringSetPreferencesKey("allowed_numbers")
        val BLOCKED_NUMBERS = stringSetPreferencesKey("blocked_numbers")
        val ALWAYS_ALLOW_FAVORITES = booleanPreferencesKey("always_allow_favorites")
        val SCHEDULE_ENABLED = booleanPreferencesKey("blocking_schedule_enabled")
        val SCHEDULE_START = intPreferencesKey("blocking_schedule_start_minutes")
        val SCHEDULE_END = intPreferencesKey("blocking_schedule_end_minutes")
        val CALL_SIM_ACCOUNT = stringPreferencesKey("call_sim_account_id")
        val BLOCKING_SIM_ACCOUNT = stringPreferencesKey("blocking_sim_account_id")
        val BLOCKED_CALL_ACTION = stringPreferencesKey("blocked_call_action")
        val ARK_INTERNET_CALLS = booleanPreferencesKey("ark_internet_calls_enabled")
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
                blockAllCallers = preferences[Keys.BLOCK_ALL] ?: false,
                blockHiddenNumbers = preferences[Keys.BLOCK_HIDDEN] ?: false,
                blockUnknownCallers = preferences[Keys.BLOCK_UNKNOWN] ?: false,
                blockedPrefixes = preferences[Keys.BLOCKED_PREFIXES] ?: emptySet(),
                allowRepeatCallers = preferences[Keys.ALLOW_REPEAT_CALLERS] ?: true,
                repeatCallerWindowMinutes = (
                    preferences[Keys.REPEAT_WINDOW] ?: Settings.DEFAULT_REPEAT_WINDOW_MINUTES
                    ).coerceIn(
                    Settings.MIN_REPEAT_WINDOW_MINUTES,
                    Settings.MAX_REPEAT_WINDOW_MINUTES,
                ),
                allowedNumbers = preferences[Keys.ALLOWED_NUMBERS] ?: emptySet(),
                blockedNumbers = preferences[Keys.BLOCKED_NUMBERS] ?: emptySet(),
                alwaysAllowFavorites = preferences[Keys.ALWAYS_ALLOW_FAVORITES] ?: true,
                blockingScheduleEnabled = preferences[Keys.SCHEDULE_ENABLED] ?: false,
                blockingScheduleStartMinutes = preferences[Keys.SCHEDULE_START]
                    ?: Settings.DEFAULT_SCHEDULE_START_MINUTES,
                blockingScheduleEndMinutes = preferences[Keys.SCHEDULE_END]
                    ?: Settings.DEFAULT_SCHEDULE_END_MINUTES,
                callSimAccountId = preferences[Keys.CALL_SIM_ACCOUNT]?.takeIf { it.isNotBlank() },
                blockingSimAccountId = preferences[Keys.BLOCKING_SIM_ACCOUNT]
                    ?.takeIf { it.isNotBlank() },
                blockedCallAction = preferences[Keys.BLOCKED_CALL_ACTION]
                    ?.let { stored -> BlockedCallAction.entries.firstOrNull { it.name == stored } }
                    ?: BlockedCallAction.REJECT,
                arkInternetCallsEnabled = preferences[Keys.ARK_INTERNET_CALLS] ?: true,
            )
        }

    override suspend fun setAnnounceMode(mode: AnnounceMode) {
        dataStore.edit { it[Keys.ANNOUNCE_MODE] = mode.name }
    }

    override suspend fun setBlockedCallAction(action: BlockedCallAction) {
        dataStore.edit { it[Keys.BLOCKED_CALL_ACTION] = action.name }
    }

    override suspend fun setArkInternetCallsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ARK_INTERNET_CALLS] = enabled }
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

    override suspend fun setBlockAllCallers(enabled: Boolean) {
        dataStore.edit { it[Keys.BLOCK_ALL] = enabled }
    }

    override suspend fun setBlockHiddenNumbers(enabled: Boolean) {
        dataStore.edit { it[Keys.BLOCK_HIDDEN] = enabled }
    }

    override suspend fun setBlockUnknownCallers(enabled: Boolean) {
        dataStore.edit { it[Keys.BLOCK_UNKNOWN] = enabled }
    }

    override suspend fun addBlockedPrefix(prefix: String) {
        addTo(Keys.BLOCKED_PREFIXES, prefix)
    }

    override suspend fun removeBlockedPrefix(prefix: String) {
        removeFrom(Keys.BLOCKED_PREFIXES, prefix)
    }

    override suspend fun addAllowedNumber(number: String) {
        addTo(Keys.ALLOWED_NUMBERS, number)
    }

    override suspend fun removeAllowedNumber(number: String) {
        removeFrom(Keys.ALLOWED_NUMBERS, number)
    }

    override suspend fun addBlockedNumber(number: String) {
        addTo(Keys.BLOCKED_NUMBERS, number)
    }

    override suspend fun removeBlockedNumber(number: String) {
        removeFrom(Keys.BLOCKED_NUMBERS, number)
    }

    override suspend fun setCallSimAccountId(accountId: String?) {
        setOrClear(Keys.CALL_SIM_ACCOUNT, accountId)
    }

    override suspend fun setBlockingSimAccountId(accountId: String?) {
        setOrClear(Keys.BLOCKING_SIM_ACCOUNT, accountId)
    }

    private suspend fun setOrClear(key: Preferences.Key<String>, value: String?) {
        dataStore.edit {
            if (value.isNullOrBlank()) it.remove(key) else it[key] = value
        }
    }

    private suspend fun addTo(key: Preferences.Key<Set<String>>, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { it[key] = (it[key] ?: emptySet()) + trimmed }
    }

    private suspend fun removeFrom(key: Preferences.Key<Set<String>>, value: String) {
        dataStore.edit { it[key] = (it[key] ?: emptySet()) - value }
    }

    override suspend fun setAllowRepeatCallers(enabled: Boolean) {
        dataStore.edit { it[Keys.ALLOW_REPEAT_CALLERS] = enabled }
    }

    override suspend fun setRepeatCallerWindowMinutes(minutes: Int) {
        dataStore.edit {
            it[Keys.REPEAT_WINDOW] = minutes.coerceIn(
                Settings.MIN_REPEAT_WINDOW_MINUTES,
                Settings.MAX_REPEAT_WINDOW_MINUTES,
            )
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
