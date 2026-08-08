package org.jarsi.arkphone.voip

import org.jarsi.arkphone.util.nationalSignificantDigits

/**
 * ARK codes as the signaling worker defines them (worker/docs/protocol.md §1):
 * `ARK-XXXX-XXXX` over a 31-character alphabet with no 0, O, 1, I or L.
 * The worker anchors its pattern, so leading or trailing whitespace is a
 * rejection, not something the server trims — canonicalize before sending.
 */
object ArkCode {

    const val ALPHABET: String = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    val PATTERN: Regex = Regex("^ARK-[$ALPHABET]{4}-[$ALPHABET]{4}$")

    fun isValid(code: String): Boolean = PATTERN.matches(code)

    /**
     * Accepts what a person can realistically paste — mixed case, spaces,
     * missing dashes, a trailing newline from an SMS — and returns the exact
     * 13-character form the worker accepts, or null when the input is not a
     * code at all.
     */
    fun canonicalize(input: String): String? {
        val body = input.uppercase()
            .filter { it in ALPHABET }
            .removePrefix("ARK")
        if (body.length != 8) return null
        val code = "ARK-${body.take(4)}-${body.drop(4)}"
        return code.takeIf(::isValid)
    }
}

/**
 * Key a number↔code link is stored and looked up under: the national
 * significant digits (trunk zero stripped), capped to the same nine-digit
 * tail `sameCaller` matches on, so national and international spellings of
 * the same phone land on one row. Short numbers stored only in international
 * form keep a country-code digit in the key — the same ambiguity
 * `sameCaller` resolves pairwise and a single key cannot.
 */
fun arkLinkKey(number: String): String {
    val significant = nationalSignificantDigits(number) ?: number.filter(Char::isDigit)
    return significant.takeLast(9)
}
