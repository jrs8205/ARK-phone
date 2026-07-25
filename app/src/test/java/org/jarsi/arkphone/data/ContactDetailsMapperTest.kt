package org.jarsi.arkphone.data

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import org.jarsi.arkphone.data.model.LabeledField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactDetailsMapperTest {

    private val typeLabel = { mimeType: String, type: Int, custom: String? ->
        custom ?: "$mimeType:$type"
    }

    private fun details(rows: List<ContactDataRow>) =
        assembleContactDetails(1L, "Matti Meikäläinen", null, false, rows, typeLabel)

    @Test
    fun phonesEmailsAndAddressesAreCollectedWithLabels() {
        val details = details(
            listOf(
                ContactDataRow(Phone.CONTENT_ITEM_TYPE, "+358 44 5552841", Phone.TYPE_MOBILE),
                ContactDataRow(Phone.CONTENT_ITEM_TYPE, "09 1234", Phone.TYPE_CUSTOM, "Mökki"),
                ContactDataRow(Email.CONTENT_ITEM_TYPE, "matti@example.com", Email.TYPE_HOME),
                ContactDataRow(
                    StructuredPostal.CONTENT_ITEM_TYPE,
                    "Kotikatu 1, 00100 Helsinki",
                    StructuredPostal.TYPE_HOME,
                ),
            ),
        )
        assertEquals(
            listOf(
                LabeledField("+358 44 5552841", "${Phone.CONTENT_ITEM_TYPE}:${Phone.TYPE_MOBILE}"),
                LabeledField("09 1234", "Mökki"),
            ),
            details.phones,
        )
        assertEquals(
            listOf(LabeledField("matti@example.com", "${Email.CONTENT_ITEM_TYPE}:${Email.TYPE_HOME}")),
            details.emails,
        )
        assertEquals(
            listOf(
                LabeledField(
                    "Kotikatu 1, 00100 Helsinki",
                    "${StructuredPostal.CONTENT_ITEM_TYPE}:${StructuredPostal.TYPE_HOME}",
                ),
            ),
            details.addresses,
        )
    }

    @Test
    fun eventsOrganizationNoteAndWebsitesAreCollected() {
        val details = details(
            listOf(
                ContactDataRow(Event.CONTENT_ITEM_TYPE, "1985-07-25", Event.TYPE_BIRTHDAY),
                ContactDataRow(
                    Organization.CONTENT_ITEM_TYPE,
                    "Yritys Oy",
                    Organization.TYPE_WORK,
                    null,
                    "Toimitusjohtaja",
                ),
                ContactDataRow(Note.CONTENT_ITEM_TYPE, "Tavattu messuilla"),
                ContactDataRow(Website.CONTENT_ITEM_TYPE, "https://example.com"),
            ),
        )
        assertEquals(
            listOf(LabeledField("1985-07-25", "${Event.CONTENT_ITEM_TYPE}:${Event.TYPE_BIRTHDAY}")),
            details.events,
        )
        assertEquals("Yritys Oy · Toimitusjohtaja", details.organization)
        assertEquals("Tavattu messuilla", details.note)
        assertEquals(listOf("https://example.com"), details.websites)
    }

    @Test
    fun organizationWithoutATitleIsJustTheCompany() {
        val details = details(
            listOf(ContactDataRow(Organization.CONTENT_ITEM_TYPE, "Yritys Oy")),
        )
        assertEquals("Yritys Oy", details.organization)
    }

    @Test
    fun blankValuesAndUnknownMimeTypesAreSkipped() {
        val details = details(
            listOf(
                ContactDataRow(Phone.CONTENT_ITEM_TYPE, "  "),
                ContactDataRow(Phone.CONTENT_ITEM_TYPE, null),
                ContactDataRow("vnd.example/unknown", "value"),
                ContactDataRow(Note.CONTENT_ITEM_TYPE, ""),
            ),
        )
        assertEquals(emptyList<LabeledField>(), details.phones)
        assertNull(details.note)
    }

    @Test
    fun duplicateValuesAreDeduped() {
        val details = details(
            listOf(
                ContactDataRow(Phone.CONTENT_ITEM_TYPE, "+358 44 5552841", Phone.TYPE_MOBILE),
                ContactDataRow(Phone.CONTENT_ITEM_TYPE, "+358 44 5552841", Phone.TYPE_MOBILE),
            ),
        )
        assertEquals(1, details.phones.size)
    }
}
