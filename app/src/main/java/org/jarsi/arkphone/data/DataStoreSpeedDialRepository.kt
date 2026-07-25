package org.jarsi.arkphone.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val SPEED_DIAL_DIGITS = 2..9

private fun speedDialKey(digit: Int) = stringPreferencesKey("speed_dial_$digit")

@Singleton
class DataStoreSpeedDialRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SpeedDialRepository {

    override val entries: Flow<Map<Int, String>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { preferences ->
            SPEED_DIAL_DIGITS.mapNotNull { digit ->
                preferences[speedDialKey(digit)]?.let { digit to it }
            }.toMap()
        }

    override suspend fun set(digit: Int, number: String) {
        require(digit in SPEED_DIAL_DIGITS) { "Speed dial digits are 2-9, got $digit" }
        dataStore.edit { it[speedDialKey(digit)] = number }
    }

    override suspend fun clear(digit: Int) {
        dataStore.edit { it.remove(speedDialKey(digit)) }
    }
}
