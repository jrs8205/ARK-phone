package org.jarsi.arkphone.telecom

import kotlinx.coroutines.delay
import org.jarsi.arkphone.data.CallLogRepository
import javax.inject.Inject

/**
 * Rewrites the log row of a rule-rejected call to the blocked type. The
 * in-call reject path has no platform marker for "blocked by a rule", and
 * OEMs insert the log row with a delay after reject(), so the rewrite is
 * retried until the row appears.
 */
class RejectedCallReclassifier @Inject constructor(
    private val callLogRepository: CallLogRepository,
) {

    suspend fun markBlocked(number: String, notBeforeMillis: Long): Boolean {
        repeat(MAX_ATTEMPTS) { attempt ->
            if (callLogRepository.reclassifyLatestRejectionAsBlocked(number, notBeforeMillis)) {
                return true
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
        }
        return false
    }

    companion object {
        const val MAX_ATTEMPTS = 10
        const val RETRY_DELAY_MILLIS = 1_000L
    }
}
