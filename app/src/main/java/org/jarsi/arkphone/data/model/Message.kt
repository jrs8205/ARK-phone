package org.jarsi.arkphone.data.model

enum class MessageStatus { NONE, SENDING, SENT, DELIVERED, FAILED }

/** An image (or other media) part of an MMS, addressed by its part URI. */
data class MmsAttachment(
    val partUri: String,
    val mimeType: String,
)

data class Message(
    val id: Long,
    val threadId: Long,
    /** True for MMS rows; ids overlap between the sms and mms tables. */
    val isMms: Boolean,
    val address: String,
    val body: String?,
    val timestampMillis: Long,
    val incoming: Boolean,
    val status: MessageStatus,
    val subscriptionId: Int,
    val attachments: List<MmsAttachment> = emptyList(),
    /** True for an MMS notification we failed to download; enables tap-to-retry. */
    val pendingDownload: Boolean = false,
)
