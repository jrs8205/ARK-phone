package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.Settings

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun setAnnounceMode(mode: AnnounceMode)
    suspend fun setAnnounceIntervalSeconds(seconds: Int)
    suspend fun setAnnounceWhatsApp(enabled: Boolean)

    suspend fun setBlockHiddenNumbers(enabled: Boolean)

    suspend fun setBlockUnknownCallers(enabled: Boolean)

    suspend fun setBlockedPrefixes(prefixes: Set<String>)

    suspend fun setAllowRepeatCallers(enabled: Boolean)

    suspend fun setAllowedNumbers(numbers: Set<String>)

    suspend fun setAlwaysAllowFavorites(enabled: Boolean)

    suspend fun setBlockingScheduleEnabled(enabled: Boolean)

    suspend fun setBlockingSchedule(startMinutes: Int, endMinutes: Int)
}
