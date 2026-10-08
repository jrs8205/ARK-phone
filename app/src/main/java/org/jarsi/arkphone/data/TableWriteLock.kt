package org.jarsi.arkphone.data

import dagger.Lazy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jarsi.arkphone.backup.BackupStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One writer at a time for the app-owned tables. A backup restore replaces
 * them in a transaction that it may have to attempt twice; a WhatsApp call
 * or a link recorded between the attempts would be wiped by the second.
 * SQLite's own lock only covers one attempt, so every writer queues here
 * and first finishes any restore still pending after a storage failure.
 */
@Singleton
class TableWriteLock(private val finishPending: suspend () -> Unit) {
    // Deferred to avoid constructing BackupStore while its own lock is being
    // injected. Recovery is available even before the startup task has run.
    @Inject
    constructor(store: Lazy<BackupStore>) : this({ store.get().finishPending() })

    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock {
        finishPending()
        block()
    }

    /** Restore operations perform their own recovery while holding this same mutex. */
    internal suspend fun <T> withRestoreLock(block: suspend () -> T): T = mutex.withLock { block() }
}
