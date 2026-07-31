package org.jarsi.arkphone.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.jarsi.arkphone.R

/** Contextual top bar shown while rows are selected: close, count, actions.
 *  Pass zero [windowInsets] when the host already pads the status bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    actions: @Composable RowScope.() -> Unit,
) {
    val haptics = rememberHaptics()
    TopAppBar(
        windowInsets = windowInsets,
        navigationIcon = {
            IconButton(
                onClick = {
                    haptics.click()
                    onClose()
                },
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.selection_exit),
                )
            }
        },
        title = { Text(pluralStringResource(R.plurals.selection_count, count, count)) },
        actions = actions,
    )
}
