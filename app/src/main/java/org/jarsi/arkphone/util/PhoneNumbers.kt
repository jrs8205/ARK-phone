package org.jarsi.arkphone.util

import java.util.Calendar

/** Minutes since local midnight for [epochMillis], in the device time zone. */
internal fun minutesOfDay(epochMillis: Long): Int {
    val calendar = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
}

/** Digits of an internationally formatted number ("+358…" or "00358…"), else null. */
internal fun internationalDigits(number: String): String? {
    val compact = number.filterNot { it.isWhitespace() }
    val digits = number.filter { it.isDigit() }
    return when {
        compact.startsWith("+") -> digits.ifEmpty { null }
        digits.startsWith("00") -> digits.drop(2).ifEmpty { null }
        else -> null
    }
}

/** National-form digits without the trunk zero ("0700 123" → "700123"), else null. */
internal fun nationalSignificantDigits(number: String): String? {
    if (number.filterNot { it.isWhitespace() }.startsWith("+")) return null
    val digits = number.filter { it.isDigit() }
    if (!digits.startsWith("0") || digits.startsWith("00")) return null
    return digits.drop(1).ifEmpty { null }
}

/** Loose same-caller check without the platform matcher: exact digits, or a
 *  shared 9-digit tail so national and international forms match. */
internal fun sameCaller(a: String, b: String): Boolean {
    val digitsA = a.filter { it.isDigit() }
    val digitsB = b.filter { it.isDigit() }
    if (digitsA.isEmpty() || digitsB.isEmpty()) return false
    if (digitsA == digitsB) return true
    if (digitsA.length >= 7 && digitsB.length >= 7 &&
        digitsA.takeLast(9) == digitsB.takeLast(9)
    ) {
        return true
    }
    return trunkZeroTailMatch(a, b) || trunkZeroTailMatch(b, a)
}

/** "09 1234567" is the same caller as "+358 9 1234567": short landlines miss
 *  the nine-digit shared tail, so match the national significant number
 *  against the international form's tail. Six digits minimum keeps short
 *  unrelated numbers from colliding. */
private fun trunkZeroTailMatch(national: String, international: String): Boolean {
    val significant = nationalSignificantDigits(national) ?: return false
    val intl = internationalDigits(international) ?: return false
    return significant.length >= 6 && intl.length > significant.length && intl.endsWith(significant)
}
