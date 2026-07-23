package org.jarsi.arkphone.ui.dialpad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.ui.components.clickableListItemModifier

@Composable
fun DialpadScreen(
    onCall: (String) -> Unit,
    viewModel: DialpadViewModel = hiltViewModel(),
    initialNumber: String? = null,
    onInitialNumberConsumed: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(initialNumber) {
        if (initialNumber != null) {
            viewModel.setNumber(initialNumber)
            onInitialNumberConsumed()
        }
    }
    Surface(Modifier.fillMaxSize()) {
        DialpadContent(
            uiState = uiState,
            onKey = viewModel::onKey,
            onDelete = viewModel::onDelete,
            onCall = { onCall(uiState.number) },
            onSuggestion = { number -> viewModel.setNumber(number) },
        )
    }
}

@Composable
fun DialpadContent(
    uiState: DialpadUiState,
    onKey: (Char) -> Unit,
    onDelete: () -> Unit,
    onCall: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LazyColumn(Modifier.weight(1f, fill = true)) {
            items(uiState.suggestions, key = { it.id }) { contact ->
                ListItem(
                    modifier = clickableListItemModifier {
                        contact.phoneNumber?.let(onSuggestion)
                    },
                    headlineContent = { Text(contact.displayName) },
                    supportingContent = { contact.phoneNumber?.let { Text(it) } },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = uiState.number,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = stringResource(R.string.dialpad_delete),
                )
            }
        }
        DialpadGrid(onKey = onKey)
        FloatingActionButton(
            onClick = onCall,
            modifier = Modifier.padding(top = 16.dp).size(72.dp),
        ) {
            Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.dialpad_call))
        }
    }
}
