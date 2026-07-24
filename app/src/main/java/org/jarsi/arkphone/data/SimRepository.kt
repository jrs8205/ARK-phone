package org.jarsi.arkphone.data

import org.jarsi.arkphone.data.model.SimCard

interface SimRepository {
    /** Active SIM subscriptions; empty when READ_PHONE_STATE is missing. */
    suspend fun activeSims(): List<SimCard>
}
