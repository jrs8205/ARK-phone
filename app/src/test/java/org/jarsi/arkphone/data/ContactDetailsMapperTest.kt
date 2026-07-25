package org.jarsi.arkphone.data

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import org.jarsi.arkphone.data.model.ContactAppAction
import org.jarsi.arkphone.data.model.LabeledField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactDetailsMapperTest {

    private val typeLabel = { mimeType: String, type: Int, custom: String? ->
        custom ?: "$mimeType:$type"
    }

    private fun row(
        mimeType: String,
        data1: String?,
        type: Int? = null,
        customLabel: String? = null,
        data4: String? = null,
    ) = ContactDataRow(
        id = 0,
        mimeType = mimeType,
        data1 = data1,
        typeRaw = type?.toString(),
        customLabel = customLabel,
        data4 = data4,
    )

    private fun details(rows: List<ContactDataRow>) =
        assembleContactDetails(1L, "Matti Meikäläinen", null, false, rows, typeLabel)

    @Test
    fun phonesEmailsAndAddressesAreCollectedWithLabels() {
        val details = details(
            listOf(
                row(Phone.CONTENT_ITEM_TYPE, "+358 44 5552841", Phone.TYPE_MOBILE),
                row(Phone.CONTENT_ITEM_TYPE, "09 1234", Phone.TYPE_CUSTOM, "Mökki"),
                row(Email.CONTENT_ITEM_TYPE, "matti@example.com", Email.TYPE_HOME),
                row(
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
                row(Event.CONTENT_ITEM_TYPE, "1985-07-25", Event.TYPE_BIRTHDAY),
                row(
                    Organization.CONTENT_ITEM_TYPE,
                    "Yritys Oy",
                    Organization.TYPE_WORK,
                    null,
                    "Toimitusjohtaja",
                ),
                row(Note.CONTENT_ITEM_TYPE, "Tavattu messuilla"),
                row(Website.CONTENT_ITEM_TYPE, "https://example.com"),
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
            listOf(row(Organization.CONTENT_ITEM_TYPE, "Yritys Oy")),
        )
        assertEquals("Yritys Oy", details.organization)
    }

    @Test
    fun blankValuesAndUnknownMimeTypesAreSkipped() {
        val details = details(
            listOf(
                row(Phone.CONTENT_ITEM_TYPE, "  "),
                row(Phone.CONTENT_ITEM_TYPE, null),
                row("text/plain", "value"),
                row(Note.CONTENT_ITEM_TYPE, ""),
            ),
        )
        assertEquals(emptyList<LabeledField>(), details.phones)
        assertNull(details.note)
        assertEquals(emptyList<ContactAppAction>(), details.appActions)
    }

    @Test
    fun duplicateValuesAreDeduped() {
        val details = details(
            listOf(
                row(Phone.CONTENT_ITEM_TYPE, "+358 44 5552841", Phone.TYPE_MOBILE),
                row(Phone.CONTENT_ITEM_TYPE, "+358 44 5552841", Phone.TYPE_MOBILE),
            ),
        )
        assertEquals(1, details.phones.size)
    }

    @Test
    fun thirdPartyContactActionsBecomeAppActions() {
        val details = details(
            listOf(
                ContactDataRow(
                    id = 41,
                    mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call",
                    data1 = "358445552841@s.whatsapp.net",
                    typeRaw = "WhatsApp Voice Call",
                    customLabel = "Voice call +358 44 5552841",
                ),
                ContactDataRow(
                    id = 42,
                    mimeType = "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact",
                    data1 = "+358 44 5552841",
                    typeRaw = "Signal",
                    customLabel = "Message +358 44 5552841",
                ),
            ),
        )
        assertEquals(
            listOf(
                ContactAppAction(
                    dataId = 41,
                    mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call",
                    label = "Voice call +358 44 5552841",
                ),
                ContactAppAction(
                    dataId = 42,
                    mimeType = "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact",
                    label = "Message +358 44 5552841",
                ),
            ),
            details.appActions,
        )
    }

    @Test
    fun identicalAppActionsFromLinkedRawContactsAreDeduped() {
        // Two WhatsApp accounts (or linked raw contacts) publish the same
        // actions with different data row ids — only one row should show.
        val details = details(
            listOf(
                ContactDataRow(
                    id = 41,
                    mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call",
                    data1 = "358445552841@s.whatsapp.net",
                    customLabel = "Voice call +358 44 5552841",
                ),
                ContactDataRow(
                    id = 91,
                    mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call",
                    data1 = "358445552841@s.whatsapp.net",
                    customLabel = "Voice call +358 44 5552841",
                ),
            ),
        )
        assertEquals(1, details.appActions.size)
        assertEquals(41L, details.appActions.single().dataId)
    }

    @Test
    fun whatsAppBusinessDuplicatesCollapseIntoThePlainWhatsAppAction() {
        // WhatsApp and WhatsApp Business both publish the same action for the
        // same jid under different mimetypes — one row must remain, and it
        // must be the plain WhatsApp one.
        val details = details(
            listOf(
                ContactDataRow(
                    id = 12435,
                    mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.w4b.voip.call",
                    data1 = "358405130253@s.whatsapp.net",
                    typeRaw = "WhatsApp",
                    customLabel = "Äänipuhelu: +358 40 5130253",
                ),
                ContactDataRow(
                    id = 15110,
                    mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call",
                    data1 = "358405130253@s.whatsapp.net",
                    typeRaw = "WhatsApp",
                    customLabel = "Äänipuhelu: +358 40 5130253",
                ),
            ),
        )
        val action = details.appActions.single()
        assertEquals(15110L, action.dataId)
        assertEquals("vnd.android.cursor.item/vnd.com.whatsapp.voip.call", action.mimeType)
    }

    @Test
    fun appActionsFallBackToTheRawTypeText() {
        val details = details(
            listOf(
                ContactDataRow(
                    id = 43,
                    mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.profile",
                    data1 = "358445552841@s.whatsapp.net",
                    typeRaw = "WhatsApp",
                    customLabel = null,
                ),
            ),
        )
        assertEquals(listOf(ContactAppAction(43, "vnd.android.cursor.item/vnd.com.whatsapp.profile", "WhatsApp")), details.appActions)
    }
}
