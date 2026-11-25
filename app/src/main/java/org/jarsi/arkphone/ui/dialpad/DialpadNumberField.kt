package org.jarsi.arkphone.ui.dialpad

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.awaitCancellation

private fun Char.isPhoneCharacter() = isDigit() || this == '+' || this == '*' || this == '#'

// Filter only the user's insertions. An external tel: number can already
// contain a valid post-dial pause or wait outside the pasted range.
@OptIn(ExperimentalFoundationApi::class)
private val phoneInput = InputTransformation {
    for (change in changes.changeCount - 1 downTo 0) {
        val range = changes.getRange(change)
        val inserted = asCharSequence().subSequence(range.min, range.max)
        val filtered = inserted.filter { it.isPhoneCharacter() }
        if (filtered.length != inserted.length) replace(range.min, range.max, filtered)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DialpadNumberField(field: TextFieldState, countryIso: String, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
        BasicTextField(
            state = field,
            modifier = modifier.focusRequester(focus).onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                // Keep navigation, selection and clipboard shortcuts available.
                !key.isCtrlPressed && !key.isMetaPressed &&
                    (key.unicodeChar >= 0x20 || key.keyCode == KeyEvent.KEYCODE_DEL ||
                        key.keyCode == KeyEvent.KEYCODE_FORWARD_DEL)
            },
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false),
            inputTransformation = phoneInput,
            outputTransformation = {
                val raw = asCharSequence().toString()
                val formatted = formatDialpadNumber(raw, countryIso)
                // Insert only presentation separators. Compose then maps taps
                // and selection handles back to positions in the raw number.
                if (raw != formatted && formatted.filterNot(Char::isDialSeparator) == raw) {
                    formatted.forEachIndexed { index, char ->
                        if (char.isDialSeparator()) insert(index, char.toString())
                    }
                }
            },
        )
    }
    LaunchedEffect(focus) { focus.requestFocus() }
}
