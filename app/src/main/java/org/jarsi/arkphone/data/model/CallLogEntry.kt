package org.jarsi.arkphone.data.model

enum class CallType { INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, OTHER }

/** Which app carried the call, derived from the log row's phone account. */
enum class CallSource { PHONE, WHATSAPP, OTHER }

data class CallLogEntry(
    val id: Long,
    val number: String,
    val displayName: String?,
    val type: CallType,
    val timestampMillis: Long,
    val durationSeconds: Long,
    val source: CallSource = CallSource.PHONE,
    /** The WhatsApp variant that carried the call, for callback routing. */
    val whatsAppPackage: String? = null,
)
