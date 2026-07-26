package org.jarsi.arkphone.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun Modifier.clickableListItem(onClick: () -> Unit): Modifier {
    val haptics = rememberHaptics()
    return clickable {
        haptics.click()
        onClick()
    }
}

@Composable
fun Modifier.clickableListItem(onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    val haptics = rememberHaptics()
    return combinedClickable(
        onClick = {
            haptics.click()
            onClick()
        },
        onLongClick = {
            haptics.longPress()
            onLongClick()
        },
    )
}
