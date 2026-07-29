package org.jarsi.arkphone.data.model

data class Conversation(
    val threadId: Long,
    /** Raw addresses of every participant; size > 1 means a group thread. */
    val addresses: List<String>,
    val snippet: String?,
    val timestampMillis: Long,
    val unread: Boolean,
)
