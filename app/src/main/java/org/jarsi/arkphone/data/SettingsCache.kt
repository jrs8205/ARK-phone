package org.jarsi.arkphone.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronous view of the latest settings for call-path code that cannot
 * suspend (notification building). Holds defaults until the first DataStore
 * emission, which at worst means one normally ringing call right after
 * process start.
 */
@Singleton
class SettingsCache @Inject constructor(
    settingsRepository: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    private val state = settingsRepository.settings.stateIn(scope, SharingStarted.Eagerly, Settings())

    val current: Settings get() = state.value
}
