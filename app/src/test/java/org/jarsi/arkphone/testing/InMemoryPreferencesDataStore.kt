package org.jarsi.arkphone.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A preferences store with no file behind it: any number of writes per
 * test (the file-backed store allows one on Windows), and a hook to slip
 * another writer in front of the next edit.
 */
class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    /** Runs once, right before the next edit — another writer getting in first. */
    var beforeNextUpdate: (suspend () -> Unit)? = null

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        beforeNextUpdate?.let { hook ->
            beforeNextUpdate = null
            hook()
        }
        return mutex.withLock {
            val next = transform(state.value)
            state.value = next
            next
        }
    }
}
