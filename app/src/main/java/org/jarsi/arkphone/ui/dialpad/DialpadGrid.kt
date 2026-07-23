package org.jarsi.arkphone.ui.dialpad

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jarsi.arkphone.R

private val dialpadRows = listOf(
    listOf('1' to "", '2' to "ABC", '3' to "DEF"),
    listOf('4' to "GHI", '5' to "JKL", '6' to "MNO"),
    listOf('7' to "PQRS", '8' to "TUV", '9' to "WXYZ"),
    listOf('*' to "", '0' to "+", '#' to ""),
)

@Composable
fun DialpadGrid(onKey: (Char) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        dialpadRows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { (digit, letters) ->
                    DialpadKey(
                        digit = digit,
                        letters = letters,
                        onKey = onKey,
                        onLongPress = if (digit == '0') {
                            { onKey('+') }
                        } else {
                            // A no-op (not null) so combinedClickable consumes the long-press
                            // gesture instead of falling through to a plain click on release.
                            {}
                        },
                        onLongPressLabel = if (digit == '0') {
                            stringResource(R.string.dialpad_long_press_plus)
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DialpadKey(
    digit: Char,
    letters: String,
    onKey: (Char) -> Unit,
    onLongPress: () -> Unit,
    onLongPressLabel: String? = null,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = { onKey(digit) },
                onLongClick = onLongPress,
                onLongClickLabel = onLongPressLabel,
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = digit.toString(), style = MaterialTheme.typography.headlineSmall)
                if (letters.isNotEmpty()) {
                    Text(
                        text = letters,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
