package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.Settings

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun setAnnounceMode(mode: AnnounceMode)
    suspend fun setAnnounceIntervalSeconds(seconds: Int)
    suspend fun setAnnounceWhatsApp(enabled: Boolean)
}
