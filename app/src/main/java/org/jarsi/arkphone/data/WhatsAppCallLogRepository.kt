package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.WhatsAppCallRecord

interface WhatsAppCallLogRepository {
    /** The recorded WhatsApp calls as log entries, newest first. */
    fun calls(): Flow<List<CallLogEntry>>

    suspend fun record(call: WhatsAppCallRecord)

    /** Deletes every recorded call whose number matches [number]. */
    suspend fun deleteCallsFor(number: String)

    /** Deletes calls recorded with only a name (no number) matching [name]. */
    suspend fun deleteCallsForName(name: String)
}
