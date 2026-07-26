package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import org.jarsi.arkphone.data.model.CallLogEntry

interface CallLogRepository {
    fun callLog(): Flow<List<CallLogEntry>>

    /**
     * Re-checks permission, starts provider observation if it just became
     * possible, and re-queries. Call after a runtime permission change.
     */
    fun refresh()

    /**
     * Rows dated at or after [sinceMillis] only — the call-path repeat-caller
     * check runs inside the screening deadline and must not read the full log.
     */
    suspend fun callsSince(sinceMillis: Long): List<CallLogEntry>

    /**
     * Deletes every log row whose number matches [number]. Returns false when
     * WRITE_CALL_LOG is missing or the delete failed.
     */
    suspend fun deleteCallsFor(number: String): Boolean

    /**
     * Rewrites the newest rejected row from [number] (dated at or after
     * [notBeforeMillis]) to the blocked type, so in-call rule blocks appear
     * under the Blocked filter. Returns false while the row has not been
     * inserted yet or the update failed.
     */
    suspend fun reclassifyLatestRejectionAsBlocked(number: String, notBeforeMillis: Long): Boolean
}
