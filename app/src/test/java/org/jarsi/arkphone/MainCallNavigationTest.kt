package org.jarsi.arkphone

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.telecom.FakeCallHandle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainCallNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun consumedTelIntentDoesNotOverwriteTheEditedNumberOnRecreation() {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:*123%23"), context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.onNodeWithText("Continue").performClick()
            val field = compose.onNode(hasSetTextAction())
            field.performTextInputSelection(TextRange(2))
            compose.onNodeWithText("8").performClick()
            scenario.recreate()
            val config = field.fetchSemanticsNode().config
            assertEquals("*1823#", config[SemanticsProperties.EditableText].text)
            assertEquals(TextRange(3), config[SemanticsProperties.TextSelectionRange])
        }
    }

    @Test
    fun messagesLauncherAndEveryTabCanReturnToTheLiveCall() {
        val alias = Intent(Intent.ACTION_MAIN).setClassName(context, "org.jarsi.arkphone.MessagesLauncher")
        ActivityScenario.launch<MainActivity>(alias).use { scenario ->
            compose.onNodeWithText("Continue").performClick()
            compose.onNodeWithText("Messages").assertIsSelected()
            scenario.onActivity { it.callController.onCallAdded(FakeCallHandle(displayName = "Ada")) }
            val bar = compose.onNodeWithText("Return to call", substring = true)
            for (tab in listOf("Home", "Keypad", "Contacts", "Messages")) {
                compose.onNodeWithText(tab).performClick()
                bar.assertExists()
            }
            scenario.onActivity { it.callController.onCallRemoved("call-1") }
            bar.assertDoesNotExist()
        }
    }
}
