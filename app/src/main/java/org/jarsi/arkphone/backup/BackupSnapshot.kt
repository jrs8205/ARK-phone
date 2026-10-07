package org.jarsi.arkphone.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One preferences entry, typed so it can be written back exactly as it was. */
data class BackupPreference(val key: String, val type: Type, val value: Any) {
    enum class Type(val wire: String) {
        STRING("string"),
        INT("int"),
        LONG("long"),
        FLOAT("float"),
        DOUBLE("double"),
        BOOLEAN("boolean"),
        STRING_SET("stringSet"),
        ;

        companion object {
            fun fromWire(wire: String): Type? = entries.firstOrNull { it.wire == wire }
        }
    }
}

data class BackupArkLink(
    val numberKey: String,
    val number: String,
    val code: String,
    val nickname: String,
    val publicKey: String,
    val linkedAtMillis: Long,
)

data class BackupWhatsAppCall(
    val callerName: String?,
    val callerNumber: String?,
    val type: String,
    val timestampMillis: Long,
    val durationSeconds: Long,
    val isVideo: Boolean,
    val sourcePackage: String,
)

/** Everything the app owns, in the shape the backup file carries. */
data class BackupSnapshot(
    val createdAtMillis: Long,
    val appVersion: String,
    val preferences: List<BackupPreference>,
    val arkLinks: List<BackupArkLink>,
    val whatsAppCalls: List<BackupWhatsAppCall>,
) {
    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "createdAtMillis" to JsonPrimitive(createdAtMillis),
            "appVersion" to JsonPrimitive(appVersion),
            "preferences" to JsonArray(preferences.map { it.toJson() }),
            "arkLinks" to JsonArray(arkLinks.map { it.toJson() }),
            "whatsAppCalls" to JsonArray(whatsAppCalls.map { it.toJson() }),
        ),
    )

    companion object {
        /** Throws [IllegalArgumentException] on any shape the app does not know. */
        fun fromJson(json: JsonObject): BackupSnapshot = BackupSnapshot(
            createdAtMillis = json.required("createdAtMillis").jsonPrimitive.long,
            appVersion = json.required("appVersion").jsonPrimitive.content,
            preferences = json.required("preferences").jsonArray.map { preferenceFromJson(it.jsonObject) },
            arkLinks = json.required("arkLinks").jsonArray.map { arkLinkFromJson(it.jsonObject) },
            whatsAppCalls = json.required("whatsAppCalls").jsonArray.map { whatsAppCallFromJson(it.jsonObject) },
        )
    }
}

private fun JsonObject.required(key: String): JsonElement =
    this[key] ?: throw IllegalArgumentException("missing $key")

private fun JsonObject.nullableString(key: String): String? =
    this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

private fun BackupPreference.toJson(): JsonObject {
    val encoded: JsonElement = when (type) {
        BackupPreference.Type.STRING -> JsonPrimitive(value as String)
        BackupPreference.Type.INT -> JsonPrimitive(value as Int)
        BackupPreference.Type.LONG -> JsonPrimitive(value as Long)
        BackupPreference.Type.FLOAT -> JsonPrimitive(value as Float)
        BackupPreference.Type.DOUBLE -> JsonPrimitive(value as Double)
        BackupPreference.Type.BOOLEAN -> JsonPrimitive(value as Boolean)
        BackupPreference.Type.STRING_SET -> JsonArray((value as Set<*>).map { JsonPrimitive(it as String) }.sortedBy { it.content })
    }
    return JsonObject(
        mapOf(
            "key" to JsonPrimitive(key),
            "type" to JsonPrimitive(type.wire),
            "value" to encoded,
        ),
    )
}

private fun preferenceFromJson(json: JsonObject): BackupPreference {
    val key = json.required("key").jsonPrimitive.content
    val type = BackupPreference.Type.fromWire(json.required("type").jsonPrimitive.content)
        ?: throw IllegalArgumentException("unknown preference type")
    val raw = json.required("value")
    val value: Any = when (type) {
        BackupPreference.Type.STRING -> raw.jsonPrimitive.content
        BackupPreference.Type.INT -> raw.jsonPrimitive.int
        BackupPreference.Type.LONG -> raw.jsonPrimitive.long
        BackupPreference.Type.FLOAT -> raw.jsonPrimitive.float
        BackupPreference.Type.DOUBLE -> raw.jsonPrimitive.double
        BackupPreference.Type.BOOLEAN -> raw.jsonPrimitive.boolean
        BackupPreference.Type.STRING_SET -> raw.jsonArray.map { it.jsonPrimitive.content }.toSet()
    }
    return BackupPreference(key, type, value)
}

private fun BackupArkLink.toJson(): JsonObject = JsonObject(
    mapOf(
        "numberKey" to JsonPrimitive(numberKey),
        "number" to JsonPrimitive(number),
        "code" to JsonPrimitive(code),
        "nickname" to JsonPrimitive(nickname),
        "publicKey" to JsonPrimitive(publicKey),
        "linkedAtMillis" to JsonPrimitive(linkedAtMillis),
    ),
)

private fun arkLinkFromJson(json: JsonObject): BackupArkLink = BackupArkLink(
    numberKey = json.required("numberKey").jsonPrimitive.content,
    number = json.required("number").jsonPrimitive.content,
    code = json.required("code").jsonPrimitive.content,
    nickname = json.required("nickname").jsonPrimitive.content,
    publicKey = json.required("publicKey").jsonPrimitive.content,
    linkedAtMillis = json.required("linkedAtMillis").jsonPrimitive.long,
)

private fun BackupWhatsAppCall.toJson(): JsonObject = JsonObject(
    mapOf(
        "callerName" to JsonPrimitive(callerName),
        "callerNumber" to JsonPrimitive(callerNumber),
        "type" to JsonPrimitive(type),
        "timestampMillis" to JsonPrimitive(timestampMillis),
        "durationSeconds" to JsonPrimitive(durationSeconds),
        "isVideo" to JsonPrimitive(isVideo),
        "sourcePackage" to JsonPrimitive(sourcePackage),
    ),
)

private fun whatsAppCallFromJson(json: JsonObject): BackupWhatsAppCall = BackupWhatsAppCall(
    callerName = json.nullableString("callerName"),
    callerNumber = json.nullableString("callerNumber"),
    type = json.required("type").jsonPrimitive.content,
    timestampMillis = json.required("timestampMillis").jsonPrimitive.long,
    durationSeconds = json.required("durationSeconds").jsonPrimitive.long,
    isVideo = json.required("isVideo").jsonPrimitive.boolean,
    sourcePackage = json.required("sourcePackage").jsonPrimitive.content,
)
