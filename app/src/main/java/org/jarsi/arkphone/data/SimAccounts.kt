package org.jarsi.arkphone.data

import org.jarsi.arkphone.data.model.SimAccount

/** The SIM an outgoing call should use, or null for the system default —
 *  which is also the answer when the chosen SIM is no longer present. */
internal fun selectedSimAccount(chosenId: String?, available: List<SimAccount>): SimAccount? =
    available.firstOrNull { it.id == chosenId }

/** The SIM name to show beside a call, or null when there is nothing worth
 *  showing: one SIM, an unknown account, or no account recorded at all. */
internal fun simLabelFor(accountId: String?, available: List<SimAccount>): String? {
    if (available.size < 2) return null
    return available.firstOrNull { it.id == accountId }?.label
}
