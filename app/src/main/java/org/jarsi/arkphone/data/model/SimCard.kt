package org.jarsi.arkphone.data.model

data class SimCard(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String?,
    val carrierName: String?,
    val number: String?,
    val countryIso: String?,
    val isEmbedded: Boolean,
)
