package org.jarsi.arkphone.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {

    private val snapshot = BackupSnapshot(
        createdAtMillis = 1_700_000_000_000L,
        appVersion = "1.28",
        preferences = listOf(
            BackupPreference("announce_caller", BackupPreference.Type.BOOLEAN, true),
            BackupPreference("announce_interval_seconds", BackupPreference.Type.INT, 7),
            BackupPreference("some_long", BackupPreference.Type.LONG, 1L shl 40),
            BackupPreference("some_float", BackupPreference.Type.FLOAT, 1.5f),
            BackupPreference("some_double", BackupPreference.Type.DOUBLE, 2.25),
            BackupPreference("ark_code", BackupPreference.Type.STRING, "ARK-ABCD-EFGH"),
            BackupPreference("blocked_prefixes", BackupPreference.Type.STRING_SET, setOf("+358700", "0700")),
        ),
        arkLinks = listOf(
            BackupArkLink(
                numberKey = "358445552841",
                number = "+358 44 5552841",
                code = "ARK-E5HU-JVA8",
                nickname = "Jarsi",
                publicKey = "pk",
                linkedAtMillis = 1_700_000_000_001L,
            ),
        ),
        whatsAppCalls = listOf(
            BackupWhatsAppCall(
                callerName = "Alice",
                callerNumber = "+358401234567",
                type = "INCOMING",
                timestampMillis = 1_700_000_000_002L,
                durationSeconds = 61,
                isVideo = false,
                sourcePackage = "com.whatsapp",
            ),
        ),
    )

    private val codec = BackupCodec()

    @Test
    fun anUnencryptedFileRoundTripsEveryPreferenceType() {
        val bytes = codec.encode(snapshot, password = null)
        val file = codec.inspect(bytes)
        assertFalse(file.encrypted)
        assertEquals(snapshot, codec.decode(bytes, password = null))
    }

    @Test
    fun anEncryptedFileRoundTripsWithThePassword() {
        val bytes = codec.encode(snapshot, password = "correct horse", iterations = 1_000)
        assertTrue(codec.inspect(bytes).encrypted)
        assertEquals(snapshot, codec.decode(bytes, password = "correct horse"))
        assertFalse("the ARK code must not be readable without the password", String(bytes).contains("ARK-ABCD-EFGH"))
    }

    @Test
    fun theWrongPasswordIsReportedAsWrongPasswordOrDamaged() {
        val bytes = codec.encode(snapshot, password = "correct horse", iterations = 1_000)
        try {
            codec.decode(bytes, password = "battery staple")
            fail("expected BackupException")
        } catch (e: BackupException) {
            assertEquals(BackupError.WrongPasswordOrDamaged, e.error)
        }
    }

    @Test
    fun anEncryptedFileWithoutAPasswordAsksForOne() {
        val bytes = codec.encode(snapshot, password = "pw", iterations = 1_000)
        try {
            codec.decode(bytes, password = null)
            fail("expected BackupException")
        } catch (e: BackupException) {
            assertEquals(BackupError.PasswordRequired, e.error)
        }
    }

    @Test
    fun somethingElseThanABackupIsRefused() {
        for (junk in listOf("", "hello", "{}", "{\"format\":\"other\",\"version\":1}", "\u0000\u0001")) {
            try {
                codec.inspect(junk.toByteArray())
                fail("expected BackupException for $junk")
            } catch (e: BackupException) {
                assertEquals(BackupError.NotABackup, e.error)
            }
        }
    }

    @Test
    fun aNewerFormatVersionIsRefusedByNumber() {
        val bytes = "{\"format\":\"arkphone-backup\",\"version\":2,\"encrypted\":false,\"payload\":{}}".toByteArray()
        try {
            codec.decode(bytes, password = null)
            fail("expected BackupException")
        } catch (e: BackupException) {
            assertEquals(BackupError.UnsupportedVersion(2), e.error)
        }
    }

    @Test
    fun anAbsurdIterationCountIsRefusedBeforeAnyKeyDerivation() {
        val bytes = codec.encode(snapshot, password = "pw", iterations = 1_000)
        val tampered = String(bytes).replace("\"iterations\":1000", "\"iterations\":900000000").toByteArray()
        try {
            codec.decode(tampered, password = "pw")
            fail("expected BackupException")
        } catch (e: BackupException) {
            assertEquals(BackupError.WrongPasswordOrDamaged, e.error)
        }
    }
}
