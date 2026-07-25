package org.jarsi.arkphone.data.model

/** One finished WhatsApp call as observed from its notifications. */
data class WhatsAppCallRecord(
    val callerName: String?,
    val callerNumber: String?,
    val type: CallType,
    val timestampMillis: Long,
    val durationSeconds: Long,
    val isVideo: Boolean,
)
