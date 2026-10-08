package org.jarsi.arkphone.backup

import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup file is attacker-controlled input once it is shared around; the
 * app must never write anything into its own stores that it could not have
 * written itself (unknown keys, wrong types, crafted values, unbounded
 * sizes, impossible rows).
 */
class BackupSanitizerTest {

    private fun snapshot(
        preferences: List<BackupPreference> = emptyList(),
        links: List<BackupArkLink> = emptyList(),
        calls: List<BackupWhatsAppCall> = emptyList(),
    ) = BackupSnapshot(1L, "1.28", preferences, links, calls)

    private fun string(key: String, value: String) = BackupPreference(key, BackupPreference.Type.STRING, value)
    private fun int(key: String, value: Int) = BackupPreference(key, BackupPreference.Type.INT, value)

    private fun List<BackupPreference>.value(key: String): Any? = firstOrNull { it.key == key }?.value

    @Test
    fun valuesTheAppCouldNeverHaveWrittenAreDropped() {
        val cleaned = BackupSanitizer.sanitize(
            snapshot(
                preferences = listOf(
                    string("announce_mode", "EVIL"),
                    string("blocked_call_action", "EVIL"),
                    string("speed_dial_1", "0401234567"),
                    string("weird key!", "x"),
                    string("ark_nickname", "x".repeat(5_000)),
                    BackupPreference("blocked_numbers", BackupPreference.Type.STRING_SET, (1..3_000).map { "$it" }.toSet()),
                    string("speed_dial_2", "0401234567"),
                    BackupPreference("allowed_numbers", BackupPreference.Type.STRING_SET, setOf("+358401234567")),
                ),
            ),
        ).preferences
        assertNull(cleaned.value("announce_mode"))
        assertNull(cleaned.value("blocked_call_action"))
        assertNull(cleaned.value("speed_dial_1"))
        assertNull(cleaned.value("weird key!"))
        assertNull(cleaned.value("ark_nickname"))
        assertNull(cleaned.value("blocked_numbers"))
        assertEquals("0401234567", cleaned.value("speed_dial_2"))
        assertEquals(setOf("+358401234567"), cleaned.value("allowed_numbers"))
    }

    @Test
    fun aKnownKeyWithTheWrongTypeIsDropped() {
        // block_all_callers stored as a String would throw ClassCastException
        // from the settings reader on every later launch.
        val cleaned = BackupSanitizer.sanitize(
            snapshot(
                preferences = listOf(
                    string("block_all_callers", "true"),
                    string("announce_interval_seconds", "7"),
                    string("blocked_prefixes", "0700"),
                    BackupPreference("ark_code", BackupPreference.Type.INT, 5),
                    BackupPreference("block_hidden_numbers", BackupPreference.Type.BOOLEAN, true),
                ),
            ),
        ).preferences
        assertEquals(
            listOf(BackupPreference("block_hidden_numbers", BackupPreference.Type.BOOLEAN, true)),
            cleaned,
        )
    }

    @Test
    fun keysTheAppDoesNotKnowAreDropped() {
        val cleaned = BackupSanitizer.sanitize(
            snapshot(preferences = (1..50).map { string("mystery_$it", "v") } + listOf(int("announce_interval_seconds", 6))),
        ).preferences
        assertEquals(listOf(int("announce_interval_seconds", 6)), cleaned)
    }

    @Test
    fun everyPreferenceKeyTheAppWritesIsKnownToTheSanitizer() {
        // Guards the registry against drift: a key added to a repository
        // without a sanitizer entry would silently stop being restored.
        val holders = listOf(
            "org.jarsi.arkphone.data.DataStoreSettingsRepository\$Keys",
            "org.jarsi.arkphone.data.DataStoreArkIdentityRepository\$Keys",
        )
        val written = holders.flatMap { holder ->
            val owner = Class.forName(holder).getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            owner.javaClass.declaredFields
                .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
                .map { field -> field.apply { isAccessible = true }.get(owner) as Preferences.Key<*> }
                .map { it.name }
        }
        assertTrue(written.size >= 20)
        val missing = written.filter { it !in BackupStore.DEVICE_ONLY_KEYS && !BackupSanitizer.isKnownKey(it) }
        assertEquals(emptyList<String>(), missing)
        assertTrue(BackupSanitizer.isKnownKey("speed_dial_9"))
    }

    @Test
    fun knownEnumValuesAndRangesAreKeptOrClamped() {
        val cleaned = BackupSanitizer.sanitize(
            snapshot(
                preferences = listOf(
                    string("announce_mode", "VOICE_ONLY"),
                    string("blocked_call_action", "VOICEMAIL"),
                    int("announce_interval_seconds", 99),
                    int("repeat_caller_window_minutes", -5),
                    int("blocking_schedule_start_minutes", 99_999),
                    int("blocking_schedule_end_minutes", -1),
                ),
            ),
        ).preferences
        assertEquals("VOICE_ONLY", cleaned.value("announce_mode"))
        assertEquals("VOICEMAIL", cleaned.value("blocked_call_action"))
        assertEquals(10, cleaned.value("announce_interval_seconds"))
        assertEquals(1, cleaned.value("repeat_caller_window_minutes"))
        assertEquals(24 * 60 - 1, cleaned.value("blocking_schedule_start_minutes"))
        assertEquals(0, cleaned.value("blocking_schedule_end_minutes"))
    }

    @Test
    fun anIdentityWhoseCodeIsNotAnArkCodeIsDroppedWhole() {
        val cleaned = BackupSanitizer.sanitize(
            snapshot(
                preferences = listOf(
                    string("ark_code", "hello"),
                    string("ark_nickname", "Mallory"),
                    string("ark_device_token", "token"),
                    string("ark_synced_fcm_token", "fcm"),
                ),
            ),
        ).preferences
        assertEquals(emptyList<BackupPreference>(), cleaned)
    }

    @Test
    fun aValidIdentitySurvives() {
        val cleaned = BackupSanitizer.sanitize(
            snapshot(
                preferences = listOf(
                    string("ark_code", "ARK-E5HU-JVA8"),
                    string("ark_nickname", "Jarsi"),
                    string("ark_device_token", "token"),
                ),
            ),
        ).preferences
        assertEquals("ARK-E5HU-JVA8", cleaned.value("ark_code"))
        assertEquals("Jarsi", cleaned.value("ark_nickname"))
        assertEquals("token", cleaned.value("ark_device_token"))
    }

    @Test
    fun aDeviceTokenTheWorkerCouldNotHaveIssuedDropsTheWholeIdentity() {
        // The token becomes the Authorization header of every inbox connect;
        // OkHttp throws on a control character there, at every launch.
        val bad = listOf("token\ncontrol", "tok en", "", "t".repeat(257), "tök", "tok\u0000")
        for (token in bad) {
            val cleaned = BackupSanitizer.sanitize(
                snapshot(
                    preferences = listOf(
                        string("ark_code", "ARK-E5HU-JVA8"),
                        string("ark_nickname", "Jarsi"),
                        string("ark_device_token", token),
                    ),
                ),
            ).preferences
            assertEquals("token ${token.encodeToByteArray().toList()}", emptyList<BackupPreference>(), cleaned)
        }
        val issued = BackupSanitizer.sanitize(
            snapshot(
                preferences = listOf(
                    string("ark_code", "ARK-E5HU-JVA8"),
                    string("ark_device_token", "AbC-xYz_0123456789.~"),
                ),
            ),
        ).preferences
        assertEquals("AbC-xYz_0123456789.~", issued.value("ark_device_token"))
    }

    @Test
    fun aNumberThatIsNotAPhoneNumberNeverReachesTheDialer() {
        // Speed dials, link numbers and WhatsApp numbers all end up in a
        // call intent; an MMI sequence ("*21*…#") must not ride in on a file.
        val good = BackupArkLink("445552841", "+358 44 5552841", "ARK-E5HU-JVA8", "Jarsi", "pk", 5L)
        val goodCall = BackupWhatsAppCall("Alice", "+358401234567", "INCOMING", 9L, 61, false, "com.whatsapp")
        val cleaned = BackupSanitizer.sanitize(
            snapshot(
                preferences = listOf(
                    string("speed_dial_2", "*#06#"),
                    string("speed_dial_3", "0401234567"),
                    string("speed_dial_4", "+358 (40) 123-4567"),
                    string("speed_dial_5", "tel:0401234567"),
                    string("speed_dial_6", "040123456;7"),
                    // Contact-card spellings the app itself stores.
                    string("speed_dial_7", "(212) 555-0123"),
                    string("speed_dial_8", "09.123.4567"),
                    string("speed_dial_9", "0401234567,1"),
                ),
                links = listOf(
                    good,
                    good.copy(numberKey = "1", number = "*21*0401234567#"),
                    // The key is what the app would compute for the number, never the file's word.
                    good.copy(numberKey = "999999999"),
                    good.copy(numberKey = "1", number = "+1"),
                    good.copy(numberKey = "125550123", number = "(212) 555-0123"),
                    good.copy(numberKey = "", number = "( ) -"),
                ),
                calls = listOf(
                    goodCall,
                    goodCall.copy(callerNumber = "*100#"),
                    goodCall.copy(callerNumber = null),
                ),
            ),
        )
        assertNull(cleaned.preferences.value("speed_dial_2"))
        assertEquals("0401234567", cleaned.preferences.value("speed_dial_3"))
        assertEquals("+358 (40) 123-4567", cleaned.preferences.value("speed_dial_4"))
        assertNull(cleaned.preferences.value("speed_dial_5"))
        assertNull(cleaned.preferences.value("speed_dial_6"))
        assertEquals("(212) 555-0123", cleaned.preferences.value("speed_dial_7"))
        assertEquals("09.123.4567", cleaned.preferences.value("speed_dial_8"))
        assertNull(cleaned.preferences.value("speed_dial_9"))
        assertEquals(
            listOf(
                good,
                good.copy(numberKey = "1", number = "+1"),
                good.copy(numberKey = "125550123", number = "(212) 555-0123"),
            ),
            cleaned.arkLinks,
        )
        assertEquals(listOf(goodCall, goodCall.copy(callerNumber = null)), cleaned.whatsAppCalls)
    }

    @Test
    fun rowsTheAppCannotUseAreDropped() {
        val good = BackupArkLink("445552841", "+358 44 5552841", "ARK-E5HU-JVA8", "Jarsi", "pk", 5L)
        val goodCall = BackupWhatsAppCall("Alice", "+358401234567", "INCOMING", 9L, 61, false, "com.whatsapp")
        val cleaned = BackupSanitizer.sanitize(
            snapshot(
                links = listOf(
                    good,
                    good.copy(numberKey = "1", code = "nope"),
                    good.copy(numberKey = "", code = "ARK-AAAA-AAAA"),
                    good.copy(numberKey = "2", nickname = "n".repeat(5_000)),
                ),
                calls = listOf(
                    goodCall,
                    goodCall.copy(type = "EVIL"),
                    goodCall.copy(durationSeconds = -1),
                    goodCall.copy(callerName = "n".repeat(5_000)),
                ),
            ),
        )
        assertEquals(listOf(good), cleaned.arkLinks)
        assertEquals(listOf(goodCall), cleaned.whatsAppCalls)
    }

    @Test
    fun tooManyRowsAreCutOff() {
        val link = BackupArkLink("445552841", "+358 44 5552841", "ARK-E5HU-JVA8", "Jarsi", "pk", 5L)
        val call = BackupWhatsAppCall("Alice", "+358401234567", "INCOMING", 9L, 61, false, "com.whatsapp")
        val cleaned = BackupSanitizer.sanitize(
            snapshot(
                links = (1..5_000).map { link.copy(numberKey = "$it", number = "0$it") },
                calls = (1..50_000).map { call.copy(timestampMillis = it.toLong()) },
            ),
        )
        assertEquals(BackupSanitizer.MAX_LINKS, cleaned.arkLinks.size)
        assertEquals(BackupSanitizer.MAX_CALLS, cleaned.whatsAppCalls.size)
    }
}
