package org.jarsi.arkphone.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jarsi.arkphone.R

/** The app's search field: rounded, with the keypad-style always-visible
 *  backspace — tap deletes one character, long-press clears the query. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        trailingIcon = {
            val clearLabel = stringResource(R.string.recents_search_clear)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = {
                            haptics.click()
                            onValueChange(value.dropLast(1))
                        },
                        onLongClickLabel = clearLabel,
                        onLongClick = {
                            haptics.longPress()
                            onValueChange("")
                        },
                    )
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = stringResource(R.string.dialpad_delete),
                )
            }
        },
    )
}
