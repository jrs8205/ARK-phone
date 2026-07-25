package org.jarsi.arkphone.data.model

/** A contact field with the system's localized type label ("Mobile", "Work"…). */
data class LabeledField(val value: String, val label: String?)

/**
 * An action a connected app (WhatsApp, Signal…) has published on the contact —
 * launching its data row opens that app's message/call flow.
 */
data class ContactAppAction(
    val dataId: Long,
    val mimeType: String,
    val label: String,
)

/** Everything stored on one contact in the system contacts provider. */
data class ContactDetails(
    val id: Long,
    val displayName: String,
    val photoUri: String?,
    val starred: Boolean,
    val phones: List<LabeledField> = emptyList(),
    val emails: List<LabeledField> = emptyList(),
    val addresses: List<LabeledField> = emptyList(),
    val events: List<LabeledField> = emptyList(),
    val organization: String? = null,
    val note: String? = null,
    val websites: List<String> = emptyList(),
    val appActions: List<ContactAppAction> = emptyList(),
    val lookupKey: String? = null,
)
