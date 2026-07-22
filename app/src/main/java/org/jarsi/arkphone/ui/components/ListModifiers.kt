package org.jarsi.arkphone.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun clickableListItemModifier(onClick: () -> Unit): Modifier = Modifier.clickable(onClick = onClick)
