package org.jarsi.arkphone.data.model

data class Contact(
    val id: Long,
    val displayName: String,
    val phoneNumber: String?,
    val photoUri: String?,
    val starred: Boolean,
)

/** Result of resolving a phone number against the device contacts. */
data class ContactMatch(
    val displayName: String?,
    val photoUri: String?,
    val contactId: Long? = null,
)
