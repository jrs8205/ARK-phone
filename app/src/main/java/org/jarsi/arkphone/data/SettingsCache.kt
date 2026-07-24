package org.jarsi.arkphone.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Latest-settings holder for the call path. [current] is synchronous and holds
 * defaults until the first DataStore emission; [await] suspends until the real
 * value has loaded. The distinction matters on cold start: telecom starts the
 * process for an incoming call and rings it within the same main-thread burst,
 * before the first emission can ever be dispatched — a synchronous read there
 * is ALWAYS the default (that bug shipped in v1.3 as a loud "voice only").
 */
@Singleton
class SettingsCache @Inject constructor(
    settingsRepository: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    private val firstLoad = CompletableDeferred<Unit>()

    private val state = settingsRepository.settings
        .onEach { firstLoad.complete(Unit) }
        .stateIn(scope, SharingStarted.Eagerly, Settings())

    val current: Settings get() = state.value

    suspend fun await(): Settings {
        firstLoad.await()
        return state.value
    }
}
