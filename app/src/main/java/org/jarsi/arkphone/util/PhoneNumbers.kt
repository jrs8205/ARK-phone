package org.jarsi.arkphone.util

/** Loose same-caller check without the platform matcher: exact digits, or a
 *  shared 9-digit tail so national and international forms match. */
internal fun sameCaller(a: String, b: String): Boolean {
    val digitsA = a.filter { it.isDigit() }
    val digitsB = b.filter { it.isDigit() }
    if (digitsA.isEmpty() || digitsB.isEmpty()) return false
    if (digitsA == digitsB) return true
    return digitsA.length >= 7 && digitsB.length >= 7 &&
        digitsA.takeLast(9) == digitsB.takeLast(9)
}
