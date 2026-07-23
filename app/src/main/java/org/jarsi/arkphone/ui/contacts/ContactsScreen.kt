package org.jarsi.arkphone.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.ui.components.clickableListItemModifier

@Composable
fun ContactsScreen(
    onCall: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ContactsContent(uiState, viewModel::onQueryChange, onCall, onRequestPermissions)
}

@Composable
fun ContactsContent(
    uiState: ContactsUiState,
    onQueryChange: (String) -> Unit,
    onCall: (String) -> Unit,
    onRequestPermissions: () -> Unit,
) {
    when {
        uiState.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        !uiState.hasPermission -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.contacts_no_permission),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onRequestPermissions, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.common_grant_access))
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.contacts_search_hint)) },
                singleLine = true,
            )
            if (uiState.favorites.isEmpty() && uiState.others.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.contacts_empty), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (uiState.favorites.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.contacts_favorites),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(uiState.favorites, key = { "fav-" + it.id }) { contact ->
                            ContactRow(contact, onCall)
                        }
                    }
                    items(uiState.others, key = { it.id }) { contact ->
                        ContactRow(contact, onCall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onCall: (String) -> Unit) {
    ListItem(
        modifier = clickableListItemModifier { contact.phoneNumber?.let(onCall) },
        headlineContent = { Text(contact.displayName) },
        supportingContent = { contact.phoneNumber?.let { Text(it) } },
        leadingContent = { ContactAvatar(contact) },
    )
}

@Composable
private fun ContactAvatar(contact: Contact) {
    if (contact.photoUri != null) {
        AsyncImage(
            model = contact.photoUri,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
    } else {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = contact.displayName.first().uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
