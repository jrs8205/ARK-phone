package org.jarsi.arkphone.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The restore being applied, durable in the app's no-backup directory
 * from before the first store changes until both hold it. It carries the
 * identity like the preferences file next to it does; it lives for the
 * milliseconds of one apply unless that apply is cut short.
 */
internal class RestoreJournal(private val dir: File) {

    data class Entry(val id: String, val snapshot: BackupSnapshot)

    private val file: File get() = File(dir, FILE_NAME)

    fun write(id: String, snapshot: BackupSnapshot) {
        dir.mkdirs()
        val scratch = File(dir, "$FILE_NAME.tmp")
        val json = JsonObject(mapOf("id" to JsonPrimitive(id), "snapshot" to snapshot.toJson()))
        FileOutputStream(scratch).use { out ->
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(scratch.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Null when there is no journal, or none this version can read. */
    fun read(): Entry? {
        val current = file.takeIf { it.exists() } ?: return null
        return runCatching {
            val json = Json.parseToJsonElement(current.readText(Charsets.UTF_8)).jsonObject
            Entry(
                id = json.getValue("id").jsonPrimitive.content,
                snapshot = BackupSnapshot.fromJson(json.getValue("snapshot").jsonObject),
            )
        }.getOrNull()
    }

    fun exists(): Boolean = file.exists()

    fun delete() {
        file.delete()
    }

    private companion object {
        const val FILE_NAME = "restore.journal"
    }
}
