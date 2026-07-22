package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import org.jarsi.arkphone.data.model.CallLogEntry

interface CallLogRepository {
    fun callLog(): Flow<List<CallLogEntry>>
}
