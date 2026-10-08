package org.jarsi.arkphone.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One writer at a time for the app-owned tables. A backup restore replaces
 * them in a transaction that it may have to attempt twice; a WhatsApp call
 * or a link recorded between the attempts would be wiped by the second.
 * SQLite's own lock only covers one attempt, so every writer queues here
 * and lands on whichever tables the restore leaves behind.
 */
@Singleton
class TableWriteLock @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
