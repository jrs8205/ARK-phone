package org.jarsi.arkphone.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jarsi.arkphone.data.model.Contact

@Composable
fun ContactAvatar(contact: Contact, size: Dp = 40.dp) {
    ContactAvatar(displayName = contact.displayName, photoUri = contact.photoUri, size = size)
}

@Composable
fun ContactAvatar(
    displayName: String?,
    photoUri: String?,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    initialTextStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    if (photoUri != null) {
        AsyncImage(
            model = photoUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Surface(
            modifier = modifier.size(size),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                val initial = displayName?.firstOrNull()
                if (initial != null) {
                    Text(
                        text = initial.uppercase(),
                        style = initialTextStyle,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(size / 2),
                    )
                }
            }
        }
    }
}
