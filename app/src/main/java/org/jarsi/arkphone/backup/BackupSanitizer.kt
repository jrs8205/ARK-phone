package org.jarsi.arkphone.backup

import org.jarsi.arkphone.backup.BackupPreference.Type
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.BlockedCallAction
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.voip.ArkCode
import org.jarsi.arkphone.voip.arkLinkKey

/**
 * A backup file is untrusted input once it has been shared around. Nothing
 * reaches the app's stores unless the app could have written it itself: a
 * key the app knows, with the type the app reads it as, known enum names,
 * clamped ranges, bounded sizes, a real ARK code. Entries that fail are
 * dropped, not fatal — a backup with one odd value still restores the rest.
 */
object BackupSanitizer {

    const val MAX_STRING_LENGTH = 1_024
    const val MAX_SET_SIZE = 2_000
    const val MAX_SET_ITEM_LENGTH = 128
    const val MAX_LINKS = 2_000
    const val MAX_CALLS = 10_000
    const val MAX_ROW_TEXT_LENGTH = 256

    /**
     * Every preference the app writes, with the type it reads it back as.
     * `BackupSanitizerTest` checks this list against the repositories'
     * key objects by reflection, so a key added there without an entry
     * here fails the build rather than silently dropping out of backups.
     */
    private val KNOWN: Map<String, Type> = mapOf(
        "announce_caller" to Type.BOOLEAN,
        "announce_mode" to Type.STRING,
        "announce_interval_seconds" to Type.INT,
        "announce_whatsapp" to Type.BOOLEAN,
        "block_all_callers" to Type.BOOLEAN,
        "block_hidden_numbers" to Type.BOOLEAN,
        "block_unknown_callers" to Type.BOOLEAN,
        "blocked_prefixes" to Type.STRING_SET,
        "allow_repeat_callers" to Type.BOOLEAN,
        "repeat_caller_window_minutes" to Type.INT,
        "allowed_numbers" to Type.STRING_SET,
        "blocked_numbers" to Type.STRING_SET,
        "always_allow_favorites" to Type.BOOLEAN,
        "blocking_schedule_enabled" to Type.BOOLEAN,
        "blocking_schedule_start_minutes" to Type.INT,
        "blocking_schedule_end_minutes" to Type.INT,
        "call_sim_account_id" to Type.STRING,
        "blocking_sim_account_id" to Type.STRING,
        "blocked_call_action" to Type.STRING,
        "ark_internet_calls_enabled" to Type.BOOLEAN,
        "ark_code" to Type.STRING,
        "ark_nickname" to Type.STRING,
        "ark_device_token" to Type.STRING,
    )
    private val SPEED_DIAL_PATTERN = Regex("^speed_dial_([2-9])$")
    private val IDENTITY_KEYS = setOf("ark_code", "ark_nickname", "ark_device_token")

    /**
     * The worker issues 43 base64url characters (worker/src/registry.ts);
     * the token then rides in an Authorization header, where a control
     * character makes OkHttp throw at every inbox connect.
     */
    private val DEVICE_TOKEN_PATTERN = Regex("^[A-Za-z0-9._~-]{1,256}$")

    /**
     * Anything stored here ends up in a call intent (speed dial long-press,
     * call back from the history, an ARK link's number): digits in any of
     * the spellings a contact card uses — "(212) 555-0123", "09.123.4567" —
     * so no MMI sequence (`*21*…#`) or DTMF pause (`,` `;`) can ride in.
     */
    private val PHONE_NUMBER_PATTERN = Regex("^\\+?[0-9 ()./-]{1,64}$")

    private fun isPhoneNumber(value: String): Boolean =
        PHONE_NUMBER_PATTERN.matches(value) && value.any(Char::isDigit)

    fun isKnownKey(key: String): Boolean = key in KNOWN || SPEED_DIAL_PATTERN.matches(key)

    private fun expectedType(key: String): Type? = KNOWN[key] ?: Type.STRING.takeIf { SPEED_DIAL_PATTERN.matches(key) }

    fun sanitize(snapshot: BackupSnapshot): BackupSnapshot = snapshot.copy(
        preferences = sanitizePreferences(snapshot.preferences),
        arkLinks = snapshot.arkLinks.filter(::acceptableLink).take(MAX_LINKS),
        whatsAppCalls = snapshot.whatsAppCalls.filter(::acceptableCall).take(MAX_CALLS),
    )

    private fun sanitizePreferences(preferences: List<BackupPreference>): List<BackupPreference> {
        val kept = preferences
            .filter { it.key !in BackupStore.DEVICE_ONLY_KEYS }
            .mapNotNull(::sanitizePreference)
            .distinctBy { it.key }
        // The identity is all or nothing: a code or token the worker would
        // never have issued must not leave a half identity behind.
        val code = kept.firstOrNull { it.key == "ark_code" }?.value as? String
        val token = kept.firstOrNull { it.key == "ark_device_token" }?.value as? String
        val impossible = (code != null && !ArkCode.isValid(code)) ||
            (token != null && !DEVICE_TOKEN_PATTERN.matches(token))
        return if (impossible) kept.filter { it.key !in IDENTITY_KEYS } else kept
    }

    private fun sanitizePreference(preference: BackupPreference): BackupPreference? {
        val key = preference.key
        if (expectedType(key) != preference.type) return null
        val value = preference.value
        when (preference.type) {
            Type.STRING -> if ((value as String).length > MAX_STRING_LENGTH) return null
            Type.STRING_SET -> {
                val set = value as Set<*>
                if (set.size > MAX_SET_SIZE || set.any { (it as String).length > MAX_SET_ITEM_LENGTH }) return null
            }
            else -> Unit
        }
        return when (key) {
            "announce_mode" -> preference.takeIf { AnnounceMode.entries.any { it.name == value } }
            "blocked_call_action" -> preference.takeIf { BlockedCallAction.entries.any { it.name == value } }
            "announce_interval_seconds" -> preference.clampInt(
                Settings.MIN_ANNOUNCE_INTERVAL_SECONDS,
                Settings.MAX_ANNOUNCE_INTERVAL_SECONDS,
            )
            "repeat_caller_window_minutes" -> preference.clampInt(
                Settings.MIN_REPEAT_WINDOW_MINUTES,
                Settings.MAX_REPEAT_WINDOW_MINUTES,
            )
            "blocking_schedule_start_minutes", "blocking_schedule_end_minutes" ->
                preference.clampInt(0, 24 * 60 - 1)
            else -> if (SPEED_DIAL_PATTERN.matches(key)) {
                preference.takeIf { isPhoneNumber(value as String) }
            } else {
                preference
            }
        }
    }

    private fun BackupPreference.clampInt(min: Int, max: Int): BackupPreference =
        copy(value = (value as Int).coerceIn(min, max))

    private fun acceptableLink(link: BackupArkLink): Boolean =
        ArkCode.isValid(link.code) &&
            isPhoneNumber(link.number) &&
            // The key is what the app computes for the number, never the file's word.
            link.numberKey == arkLinkKey(link.number) &&
            link.nickname.length <= MAX_ROW_TEXT_LENGTH &&
            link.publicKey.length <= MAX_STRING_LENGTH

    private fun acceptableCall(call: BackupWhatsAppCall): Boolean =
        CallType.entries.any { it.name == call.type } &&
            call.durationSeconds >= 0 &&
            call.timestampMillis >= 0 &&
            (call.callerName?.length ?: 0) <= MAX_ROW_TEXT_LENGTH &&
            (call.callerNumber == null || isPhoneNumber(call.callerNumber)) &&
            call.sourcePackage.length <= MAX_ROW_TEXT_LENGTH
}
