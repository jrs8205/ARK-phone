package org.jarsi.arkphone.data

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import org.jarsi.arkphone.data.model.ContactAppAction
import org.jarsi.arkphone.data.model.ContactDetails
import org.jarsi.arkphone.data.model.LabeledField

/** Third-party contact actions live under this prefix (vnd.com.whatsapp…). */
private const val APP_MIME_PREFIX = "vnd.android.cursor.item/vnd."

private val BUILT_IN_MIME_TYPES = setOf(
    Phone.CONTENT_ITEM_TYPE,
    Email.CONTENT_ITEM_TYPE,
    StructuredPostal.CONTENT_ITEM_TYPE,
    Event.CONTENT_ITEM_TYPE,
    Organization.CONTENT_ITEM_TYPE,
    Note.CONTENT_ITEM_TYPE,
    Website.CONTENT_ITEM_TYPE,
)

/** One ContactsContract.Data row reduced to what the mapper needs. */
internal data class ContactDataRow(
    val id: Long,
    val mimeType: String,
    val data1: String?,
    /** DATA2 as text — an int type for built-in rows, free text for app rows. */
    val typeRaw: String? = null,
    val customLabel: String? = null,
    val data4: String? = null,
)

/**
 * Assembles a contact's details from its raw data rows. Type labels come
 * through [typeLabel] so the production resolver can use the system's
 * localized labels while tests stay pure.
 */
internal fun assembleContactDetails(
    id: Long,
    displayName: String,
    photoUri: String?,
    starred: Boolean,
    rows: List<ContactDataRow>,
    typeLabel: (mimeType: String, type: Int, customLabel: String?) -> String?,
    lookupKey: String? = null,
): ContactDetails {
    val phones = mutableListOf<LabeledField>()
    val emails = mutableListOf<LabeledField>()
    val addresses = mutableListOf<LabeledField>()
    val events = mutableListOf<LabeledField>()
    val websites = mutableListOf<String>()
    val appActionRows = mutableListOf<Pair<String, ContactAppAction>>()
    var organization: String? = null
    var note: String? = null
    for (row in rows) {
        val value = row.data1?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        if (row.mimeType !in BUILT_IN_MIME_TYPES) {
            if (row.mimeType.startsWith(APP_MIME_PREFIX)) {
                val actionLabel = row.customLabel?.takeIf { it.isNotBlank() }
                    ?: row.typeRaw?.takeIf { it.isNotBlank() }
                if (actionLabel != null) {
                    appActionRows += value to ContactAppAction(row.id, row.mimeType, actionLabel)
                }
            }
            continue
        }
        val label = row.typeRaw?.toIntOrNull()?.let { typeLabel(row.mimeType, it, row.customLabel) }
            ?: row.customLabel
        when (row.mimeType) {
            Phone.CONTENT_ITEM_TYPE -> phones += LabeledField(value, label)
            Email.CONTENT_ITEM_TYPE -> emails += LabeledField(value, label)
            StructuredPostal.CONTENT_ITEM_TYPE -> addresses += LabeledField(value, label)
            Event.CONTENT_ITEM_TYPE -> events += LabeledField(value, label)
            Organization.CONTENT_ITEM_TYPE -> if (organization == null) {
                organization = listOfNotNull(
                    value,
                    row.data4?.trim()?.takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
            }
            Note.CONTENT_ITEM_TYPE -> if (note == null) note = value
            Website.CONTENT_ITEM_TYPE -> websites += value
        }
    }
    return ContactDetails(
        id = id,
        displayName = displayName,
        photoUri = photoUri,
        starred = starred,
        phones = phones.distinct(),
        emails = emails.distinct(),
        addresses = addresses.distinct(),
        events = events.distinct(),
        organization = organization,
        note = note,
        websites = websites.distinct(),
        // WhatsApp and WhatsApp Business (and linked raw contacts) publish the
        // same action for the same target under different mimetypes/row ids —
        // dedupe on target + label; the alphabetically first mimetype keeps
        // plain WhatsApp ahead of its .w4b variant.
        appActions = appActionRows
            .groupBy { (target, action) -> target to action.label }
            .map { (_, group) -> group.minBy { (_, action) -> action.mimeType }.second },
        lookupKey = lookupKey,
    )
}
