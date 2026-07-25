package org.jarsi.arkphone.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun clickableListItemModifier(onClick: () -> Unit): Modifier {
    val haptics = rememberHaptics()
    return Modifier.clickable {
        haptics.click()
        onClick()
    }
}

@Composable
fun clickableListItemModifier(onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    val haptics = rememberHaptics()
    return Modifier.combinedClickable(
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
