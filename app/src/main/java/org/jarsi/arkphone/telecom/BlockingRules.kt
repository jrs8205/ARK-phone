package org.jarsi.arkphone.telecom

import org.jarsi.arkphone.data.model.Settings

/**
 * The call blocking rule: hidden numbers, callers not in contacts and
 * blocked prefixes, with the repeat-caller exception on top.
 */
internal fun shouldBlockCall(
    number: String?,
    isInContacts: Boolean,
    isRepeatCaller: Boolean,
    settings: Settings,
): Boolean {
    if (number.isNullOrBlank()) return settings.blockHiddenNumbers
    if (isRepeatCaller && settings.allowRepeatCallers) return false
    if (settings.blockedPrefixes.any { matchesBlockedPrefix(number, it) }) return true
    return settings.blockUnknownCallers && !isInContacts
}

private fun matchesBlockedPrefix(number: String, prefix: String): Boolean {
    val cleanNumber = number.filter { it.isDigit() || it == '+' }
    val cleanPrefix = prefix.filter { it.isDigit() || it == '+' }
    return cleanPrefix.isNotEmpty() && cleanNumber.startsWith(cleanPrefix)
}
