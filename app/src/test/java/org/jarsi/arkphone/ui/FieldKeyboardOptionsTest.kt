package org.jarsi.arkphone.ui

import android.net.Uri
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.ui.components.SearchField
import org.jarsi.arkphone.ui.contactcard.ArkLinkDialog
import org.jarsi.arkphone.ui.contactcard.ContactCardUiState
import org.jarsi.arkphone.ui.conversation.ConversationContent
import org.jarsi.arkphone.ui.conversation.ConversationUiState
import org.jarsi.arkphone.ui.settings.ArkCallsContent
import org.jarsi.arkphone.ui.settings.ArkCallsUiState
import org.jarsi.arkphone.ui.settings.BackupContent
import org.jarsi.arkphone.ui.settings.BackupUiState
import org.jarsi.arkphone.ui.settings.BlockingContent
import org.jarsi.arkphone.ui.settings.PendingRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FieldKeyboardOptionsTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalComposeUiApi::class)
    private fun editor(index: Int = 0, content: @Composable () -> Unit): EditorInfo {
        var editor: EditorInfo? = null
        compose.setContent {
            InterceptPlatformTextInput(interceptor = { request, _ ->
                editor = EditorInfo().also { request.createInputConnection(it) }
                awaitCancellation()
            }, content = content)
        }
        compose.onAllNodes(hasSetTextAction())[index].requestFocus()
        compose.waitUntil { editor != null }
        return requireNotNull(editor)
    }

    private val capitals = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_CAP_WORDS or
        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    @Test fun nicknameRequestsWords() {
        val info = editor { ArkCallsContent(ArkCallsUiState(available = true), {}) }
        assertEquals(InputType.TYPE_TEXT_FLAG_CAP_WORDS, info.inputType and capitals)
    }

    @Test fun sharedSearchRequestsWords() {
        val info = editor { SearchField("", {}, "Search") }
        assertEquals(InputType.TYPE_TEXT_FLAG_CAP_WORDS, info.inputType and capitals)
    }

    @Test fun messageRequestsSentences() {
        val info = editor { ConversationContent(ConversationUiState(address = "+358123"), {}, {}, {}, {}, {}) }
        assertEquals(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, info.inputType and capitals)
    }

    @Test fun linkCodeRequestsAsciiCapitalsWithoutAutocorrection() {
        val info = editor { ArkLinkDialog(ContactCardUiState(), {}, {}, {}) }
        assertEquals(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, info.inputType and capitals)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII != 0)
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
    }

    private fun checkNumber(index: Int) {
        val info = editor(index) { BlockingContent(Settings(), {}) }
        assertEquals(InputType.TYPE_CLASS_PHONE, info.inputType and InputType.TYPE_MASK_CLASS)
    }

    @Test fun allowedNumberRequestsPhone() = checkNumber(0)
    @Test fun blockedPrefixRequestsPhone() = checkNumber(1)
    @Test fun blockedNumberRequestsPhone() = checkNumber(2)

    private fun checkPassword(index: Int) {
        val info = editor(index) {
            BackupContent(BackupUiState(pendingRestore = PendingRestore(Uri.EMPTY, true)), {})
        }
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
        assertEquals(0, info.inputType and capitals)
    }

    @Test fun exportPasswordIsUncapitalized() = checkPassword(0)
    @Test fun repeatedPasswordIsUncapitalized() = checkPassword(1)
    @Test fun restorePasswordIsUncapitalized() = checkPassword(2)
}
