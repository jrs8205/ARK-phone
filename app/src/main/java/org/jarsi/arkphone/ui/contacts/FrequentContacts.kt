package org.jarsi.arkphone.ui.contacts

import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.Contact

/** The most-called saved contacts, favorites excluded (they have their own row). */
internal fun frequentContacts(
    contacts: List<Contact>,
    log: List<CallLogEntry>,
    limit: Int = 5,
): List<Contact> {
    val callCounts = log
        .mapNotNull { entry -> callerKey(entry.number) }
        .groupingBy { it }
        .eachCount()
    return contacts
        .filterNot { it.starred }
        .mapNotNull { contact ->
            val key = contact.phoneNumber?.let(::callerKey) ?: return@mapNotNull null
            val count = callCounts[key] ?: return@mapNotNull null
            contact to count
        }
        .sortedByDescending { (_, count) -> count }
        .take(limit)
        .map { (contact, _) -> contact }
}

/** National and international forms must count together — key on the digit tail. */
private fun callerKey(number: String): String? =
    number.filter { it.isDigit() }.takeLast(9).takeIf { it.isNotEmpty() }
