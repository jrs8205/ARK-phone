package org.jarsi.arkphone.ui.recents

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecentsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun entry(
        source: CallSource = CallSource.PHONE,
        number: String = "0401234567",
        name: String? = "Matti Meikäläinen",
    ) = CallLogEntry(
        id = 1,
        number = number,
        displayName = name,
        type = CallType.INCOMING,
        timestampMillis = 0,
        durationSeconds = 10,
        source = source,
    )

    private fun setContent(
        entry: CallLogEntry,
        count: Int = 1,
        onOpenDetails: (String) -> Unit = {},
        onOpenNameDetails: (String) -> Unit = {},
        onWhatsAppCall: (CallLogEntry) -> Unit = {},
    ) {
        composeRule.setContent {
            RecentsContent(
                uiState = RecentsUiState(
                    loading = false,
                    entries = listOf(GroupedCallLogEntry(entry, count)),
                ),
                onCall = {},
                onRequestPermissions = {},
                onOpenDetails = onOpenDetails,
                onOpenNameDetails = onOpenNameDetails,
                onWhatsAppCall = onWhatsAppCall,
            )
        }
    }

    @Test
    fun aNumberlessWhatsAppRowOpensNameDetails() {
        val openedNames = mutableListOf<String>()
        val openedNumbers = mutableListOf<String>()
        setContent(
            entry(source = CallSource.WHATSAPP, number = ""),
            onOpenDetails = { openedNumbers += it },
            onOpenNameDetails = { openedNames += it },
        )
        composeRule.onNodeWithText("Matti Meikäläinen").performClick()
        assertEquals(listOf("Matti Meikäläinen"), openedNames)
        assertEquals(emptyList<String>(), openedNumbers)
    }

    @Test
    fun longPressOnARowCopiesTheNumber() {
        setContent(entry())
        composeRule.onNodeWithText("Matti Meikäläinen").performTouchInput { longClick() }
        val clipboard = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("0401234567", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun groupedRowShowsTheCallCount() {
        setContent(entry(), count = 3)
        composeRule.onNodeWithText("Matti Meikäläinen (3)").assertExists()
    }

    @Test
    fun whatsAppRowCallsBackThroughTheWhatsAppButton() {
        var called: CallLogEntry? = null
        val entry = entry(source = CallSource.WHATSAPP)
        setContent(entry, onWhatsAppCall = { called = it })
        composeRule.onNodeWithContentDescription("Call on WhatsApp").performClick()
        assertEquals(entry, called)
    }

    @Test
    fun phoneRowKeepsThePlainCallButton() {
        setContent(entry(source = CallSource.PHONE))
        composeRule.onNodeWithContentDescription("Call").performClick()
        composeRule.onAllNodesWithContentDescription("Call on WhatsApp").assertCountEquals(0)
    }

    @Test
    fun aRowWithoutANumberDoesNotOpenDetails() {
        var opened: String? = null
        setContent(
            entry(source = CallSource.WHATSAPP, number = ""),
            onOpenDetails = { opened = it },
        )
        composeRule.onNodeWithText("Matti Meikäläinen").performClick()
        assertNull(opened)
    }
}
