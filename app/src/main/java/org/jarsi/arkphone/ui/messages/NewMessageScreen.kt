package org.jarsi.arkphone.ui.messages

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.ui.components.ContactAvatar
import org.jarsi.arkphone.ui.components.RowCard
import org.jarsi.arkphone.ui.components.SearchField
import org.jarsi.arkphone.ui.components.clickableListItem
import org.jarsi.arkphone.ui.components.transparentListItemColors
import androidx.compose.ui.unit.dp

@Composable
fun NewMessageScreen(
    onBack: () -> Unit,
    onPick: (String) -> Unit,
) {
    val viewModel: NewMessageViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    NewMessageContent(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onBack = onBack,
        onPick = onPick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageContent(
    uiState: NewMessageUiState,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.messages_new)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                SearchField(
                    value = uiState.query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.new_message_recipient_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("recipient_query"),
                )
            }
            uiState.typedNumber?.let { number ->
                item(key = "typed-number") {
                    RowCard(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        ListItem(
                            colors = transparentListItemColors(),
                            modifier = Modifier
                                .clickableListItem(onClick = { onPick(number) })
                                .testTag("typed_number_row"),
                            leadingContent = {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            },
                            headlineContent = {
                                Text(stringResource(R.string.new_message_send_to_number, number))
                            },
                        )
                    }
                }
            }
            items(uiState.contacts, key = { it.id }) { contact ->
                ContactRow(contact, onPick)
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onPick: (String) -> Unit) {
    val number = contact.phoneNumber ?: return
    RowCard(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        ListItem(
            colors = transparentListItemColors(),
            modifier = Modifier.clickableListItem(onClick = { onPick(number) }),
            leadingContent = { ContactAvatar(contact) },
            headlineContent = { Text(contact.displayName) },
            supportingContent = { Text(number) },
        )
    }
}
