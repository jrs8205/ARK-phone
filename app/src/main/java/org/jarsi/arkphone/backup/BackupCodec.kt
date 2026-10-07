package org.jarsi.arkphone.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

sealed class BackupError {
    data object NotABackup : BackupError()
    data class UnsupportedVersion(val version: Int) : BackupError()
    data object PasswordRequired : BackupError()
    data object WrongPasswordOrDamaged : BackupError()
    data object Io : BackupError()
    data object CallInProgress : BackupError()
}

class BackupException(val error: BackupError, cause: Throwable? = null) : Exception(error.toString(), cause)

data class BackupFileInfo(val version: Int, val encrypted: Boolean)

/**
 * The backup file: a JSON envelope around the [BackupSnapshot] payload,
 * optionally AES-256-GCM encrypted under a PBKDF2 key. See
 * docs/specs/2026-10-07-backup-file-design.md for the exact layout.
 */
class BackupCodec(private val random: SecureRandom = SecureRandom()) {

    fun encode(snapshot: BackupSnapshot, password: String?, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        val payload = snapshot.toJson().toString().toByteArray(Charsets.UTF_8)
        val envelope = mutableMapOf<String, JsonElement>(
            "format" to JsonPrimitive(FORMAT),
            "version" to JsonPrimitive(VERSION),
            "encrypted" to JsonPrimitive(password != null),
        )
        if (password == null) {
            envelope["payload"] = Json.parseToJsonElement(String(payload, Charsets.UTF_8))
        } else {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val ciphertext = cipher.doFinal(payload)
            envelope["kdf"] = JsonObject(
                mapOf(
                    "algorithm" to JsonPrimitive(KDF),
                    "iterations" to JsonPrimitive(iterations),
                    "salt" to JsonPrimitive(base64(salt)),
                ),
            )
            envelope["cipher"] = JsonObject(
                mapOf(
                    "algorithm" to JsonPrimitive(CIPHER),
                    "iv" to JsonPrimitive(base64(iv)),
                ),
            )
            envelope["ciphertext"] = JsonPrimitive(base64(ciphertext))
        }
        return JsonObject(envelope).toString().toByteArray(Charsets.UTF_8)
    }

    /** Reads only the envelope: enough to know whether to ask for a password. */
    fun inspect(bytes: ByteArray): BackupFileInfo = asBackupError(BackupError.NotABackup) {
        val envelope = parseEnvelope(bytes)
        BackupFileInfo(
            version = (envelope["version"] as? JsonPrimitive)?.intOrNull ?: throw BackupException(BackupError.NotABackup),
            encrypted = (envelope["encrypted"] as? JsonPrimitive)?.booleanOrNull ?: throw BackupException(BackupError.NotABackup),
        )
    }

    fun decode(bytes: ByteArray, password: String?): BackupSnapshot = asBackupError(BackupError.NotABackup) {
        val envelope = parseEnvelope(bytes)
        val info = inspect(bytes)
        if (info.version != VERSION) throw BackupException(BackupError.UnsupportedVersion(info.version))
        val payload: JsonObject = if (!info.encrypted) {
            envelope["payload"] as? JsonObject ?: throw BackupException(BackupError.NotABackup)
        } else {
            if (password == null) throw BackupException(BackupError.PasswordRequired)
            decrypt(envelope, password)
        }
        asBackupError(if (info.encrypted) BackupError.WrongPasswordOrDamaged else BackupError.NotABackup) {
            BackupSnapshot.fromJson(payload)
        }
    }

    /**
     * The file is untrusted: kotlinx throws IllegalArgumentException,
     * IllegalStateException, NumberFormatException or ClassCastException on
     * shapes it does not expect, and none of them may escape as a crash.
     */
    private inline fun <T> asBackupError(error: BackupError, block: () -> T): T = try {
        block()
    } catch (e: BackupException) {
        throw e
    } catch (e: RuntimeException) {
        throw BackupException(error, e)
    }

    private fun decrypt(envelope: JsonObject, password: String): JsonObject = asBackupError(BackupError.WrongPasswordOrDamaged) {
        val damaged = { cause: Throwable? -> BackupException(BackupError.WrongPasswordOrDamaged, cause) }
        val kdf = envelope["kdf"] as? JsonObject ?: throw damaged(null)
        val cipherSpec = envelope["cipher"] as? JsonObject ?: throw damaged(null)
        if (kdf["algorithm"]?.jsonPrimitive?.contentOrNull != KDF) throw damaged(null)
        if (cipherSpec["algorithm"]?.jsonPrimitive?.contentOrNull != CIPHER) throw damaged(null)
        // A crafted file must not be able to pin the CPU for minutes.
        val iterations = kdf["iterations"]?.jsonPrimitive?.intOrNull ?: throw damaged(null)
        if (iterations !in 1..MAX_ITERATIONS) throw damaged(null)
        val plaintext = try {
            val salt = unbase64(kdf["salt"]?.jsonPrimitive?.contentOrNull ?: throw damaged(null))
            val iv = unbase64(cipherSpec["iv"]?.jsonPrimitive?.contentOrNull ?: throw damaged(null))
            val ciphertext = unbase64(envelope["ciphertext"]?.jsonPrimitive?.contentOrNull ?: throw damaged(null))
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw damaged(e)
        } catch (e: IllegalArgumentException) {
            throw damaged(e)
        }
        Json.parseToJsonElement(String(plaintext, Charsets.UTF_8)).jsonObject
    }

    private fun parseEnvelope(bytes: ByteArray): JsonObject {
        val element = try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupError.NotABackup, e)
        }
        val envelope = element as? JsonObject ?: throw BackupException(BackupError.NotABackup)
        val format = envelope["format"] as? JsonPrimitive
        if (format == null || !format.isString || format.content != FORMAT) throw BackupException(BackupError.NotABackup)
        return envelope
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        try {
            val key = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            return SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unbase64(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        const val FORMAT = "arkphone-backup"
        const val VERSION = 1
        const val DEFAULT_ITERATIONS = 600_000
        const val MAX_ITERATIONS = 5_000_000
        private const val KDF = "PBKDF2WithHmacSHA256"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private val AAD = "arkphone-backup:1".toByteArray(Charsets.UTF_8)
    }
}
