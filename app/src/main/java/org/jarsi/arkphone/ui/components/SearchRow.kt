package org.jarsi.arkphone.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jarsi.arkphone.R

/** The screen-top search row shared by Home and Messages: the search field
 *  with the settings gear beside it, identical on every tab that has one. */
@Composable
fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp),
    ) {
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                haptics.click()
                onOpenSettings()
            },
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.settings_title),
            )
        }
    }
}
