package org.jarsi.arkphone.data.model

data class Contact(
    val id: Long,
    val displayName: String,
    val phoneNumber: String?,
    val photoUri: String?,
    val starred: Boolean,
)
