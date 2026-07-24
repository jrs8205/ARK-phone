package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import org.jarsi.arkphone.data.model.CallLogEntry

interface CallLogRepository {
    fun callLog(): Flow<List<CallLogEntry>>

    /**
     * Deletes every log row whose number matches [number]. Returns false when
     * WRITE_CALL_LOG is missing or the delete failed.
     */
    suspend fun deleteCallsFor(number: String): Boolean
}
