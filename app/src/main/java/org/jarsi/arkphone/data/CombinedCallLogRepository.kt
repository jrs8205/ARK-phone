package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.jarsi.arkphone.data.model.CallLogEntry

/**
 * The call log the UI sees: the system log and the app-owned WhatsApp log
 * merged into one timeline.
 */
class CombinedCallLogRepository(
    private val system: CallLogRepository,
    private val whatsApp: WhatsAppCallLogRepository,
) : CallLogRepository {

    override fun callLog(): Flow<List<CallLogEntry>> =
        combine(system.callLog(), whatsApp.calls()) { systemCalls, whatsAppCalls ->
            // WhatsApp ids are negated so they can't collide with system log
            // ids where entries are keyed (list items, detail rows).
            (systemCalls + whatsAppCalls.map { it.copy(id = -it.id) })
                .sortedByDescending { it.timestampMillis }
        }

    // The Room-backed WhatsApp log needs no permission-driven refresh.
    override fun refresh() = system.refresh()

    override suspend fun deleteCallsFor(number: String): Boolean {
        val systemDeleted = system.deleteCallsFor(number)
        whatsApp.deleteCallsFor(number)
        return systemDeleted
    }

    override suspend fun reclassifyLatestRejectionAsBlocked(
        number: String,
        notBeforeMillis: Long,
    ): Boolean = system.reclassifyLatestRejectionAsBlocked(number, notBeforeMillis)
}
