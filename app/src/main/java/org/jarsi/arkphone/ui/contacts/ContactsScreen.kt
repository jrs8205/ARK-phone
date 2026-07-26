package org.jarsi.arkphone.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.ui.components.ContactAvatar
import org.jarsi.arkphone.ui.components.RowCard
import org.jarsi.arkphone.ui.components.SearchField
import org.jarsi.arkphone.ui.components.clickableListItem
import org.jarsi.arkphone.ui.components.rememberHaptics
import org.jarsi.arkphone.ui.components.transparentListItemColors
import org.jarsi.arkphone.ui.contactcard.ContactCardActivity

@Composable
fun ContactsScreen(
    onCall: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissionState()
        onPauseOrDispose { }
    }
    val context = LocalContext.current
    ContactsContent(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onCall = onCall,
        onRequestPermissions = onRequestPermissions,
        onOpenContact = { id ->
            context.startActivity(ContactCardActivity.intent(context, id))
        },
        onAddContact = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_INSERT)
                        .setType(ContactsContract.Contacts.CONTENT_TYPE),
                )
            }
        },
    )
}

@Composable
fun ContactsContent(
    uiState: ContactsUiState,
    onQueryChange: (String) -> Unit,
    onCall: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenContact: (Long) -> Unit = {},
    onAddContact: () -> Unit = {},
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
            val haptics = rememberHaptics()
            Text(
                text = stringResource(R.string.contacts_no_permission),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = {
                    haptics.click()
                    onRequestPermissions()
                },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.common_grant_access))
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            ) {
                SearchField(
                    value = uiState.query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.contacts_search_hint),
                    modifier = Modifier.weight(1f),
                )
                val haptics = rememberHaptics()
                IconButton(
                    onClick = {
                        haptics.click()
                        onAddContact()
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.PersonAdd,
                        contentDescription = stringResource(R.string.contacts_add),
                    )
                }
            }
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
                            ContactRow(contact, onCall, onOpenContact)
                        }
                    }
                    if (uiState.frequent.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.contacts_frequent),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(uiState.frequent, key = { "freq-" + it.id }) { contact ->
                            ContactRow(contact, onCall, onOpenContact)
                        }
                    }
                    if ((uiState.favorites.isNotEmpty() || uiState.frequent.isNotEmpty()) &&
                        uiState.others.isNotEmpty()
                    ) {
                        item {
                            Text(
                                text = stringResource(R.string.contacts_all),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(uiState.others, key = { it.id }) { contact ->
                        ContactRow(contact, onCall, onOpenContact)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    onCall: (String) -> Unit,
    onOpenContact: (Long) -> Unit,
) {
    RowCard(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        ContactRowItem(contact, onCall, onOpenContact)
    }
}

@Composable
private fun ContactRowItem(
    contact: Contact,
    onCall: (String) -> Unit,
    onOpenContact: (Long) -> Unit,
) {
    val haptics = rememberHaptics()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedText = stringResource(R.string.number_copied)
    ListItem(
        colors = transparentListItemColors(),
        modifier = Modifier.clickableListItem(
            onClick = { onOpenContact(contact.id) },
            onLongClick = {
                contact.phoneNumber?.let { number ->
                    clipboard.setText(AnnotatedString(number))
                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                }
            },
        ),
        headlineContent = { Text(contact.displayName) },
        supportingContent = { contact.phoneNumber?.let { Text(it) } },
        leadingContent = { ContactAvatar(contact) },
        trailingContent = {
            contact.phoneNumber?.let { number ->
                IconButton(
                    onClick = {
                        haptics.confirm()
                        onCall(number)
                    },
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = stringResource(R.string.dialpad_call),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}
