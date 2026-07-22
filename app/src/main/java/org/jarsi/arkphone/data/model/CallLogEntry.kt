package org.jarsi.arkphone.data.model

enum class CallType { INCOMING, OUTGOING, MISSED, REJECTED, OTHER }

data class CallLogEntry(
    val id: Long,
    val number: String,
    val displayName: String?,
    val type: CallType,
    val timestampMillis: Long,
    val durationSeconds: Long,
)
