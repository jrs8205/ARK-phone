package org.jarsi.arkphone.ui.messages

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.ui.components.ContactAvatar
import org.jarsi.arkphone.ui.components.RowCard
import org.jarsi.arkphone.ui.components.SearchField
import org.jarsi.arkphone.ui.components.clickableListItem
import org.jarsi.arkphone.ui.components.rememberHaptics
import org.jarsi.arkphone.ui.components.transparentListItemColors

@Composable
fun MessagesScreen(
    onOpenThread: (Long) -> Unit,
    onNewMessage: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val viewModel: MessagesViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissionState()
        onPauseOrDispose { }
    }
    MessagesContent(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onOpenThread = onOpenThread,
        onNewMessage = onNewMessage,
        onRequestPermission = onRequestPermission,
    )
}

@Composable
fun MessagesContent(
    uiState: MessagesUiState,
    onQueryChange: (String) -> Unit,
    onOpenThread: (Long) -> Unit,
    onNewMessage: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val haptics = rememberHaptics()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SearchField(
                value = uiState.query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.messages_search_placeholder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                uiState.loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                !uiState.hasReadSmsPermission -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.messages_permission_rationale),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = {
                            haptics.click()
                            onRequestPermission()
                        },
                        modifier = Modifier.padding(top = 16.dp).testTag("grant_sms"),
                    ) {
                        Text(stringResource(R.string.common_grant_access))
                    }
                }
                uiState.conversations.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.messages_empty),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(uiState.conversations, key = { it.conversation.threadId }) { item ->
                        ConversationRow(item, onOpenThread)
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = {
                haptics.confirm()
                onNewMessage()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.messages_new))
        }
    }
}

@Composable
private fun ConversationRow(item: ConversationItem, onOpenThread: (Long) -> Unit) {
    val conversation = item.conversation
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedText = stringResource(R.string.number_copied)
    val singleAddress = conversation.addresses.singleOrNull()
    RowCard(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        ListItem(
            colors = transparentListItemColors(),
            modifier = Modifier.clickableListItem(
                onClick = { onOpenThread(conversation.threadId) },
                onLongClick = {
                    if (singleAddress != null) {
                        clipboard.setText(AnnotatedString(singleAddress))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }
                },
            ),
            leadingContent = {
                ContactAvatar(
                    displayName = item.title.ifBlank { null },
                    photoUri = item.photoUri,
                )
            },
            headlineContent = {
                Text(
                    text = item.title.ifBlank { stringResource(R.string.call_log_unknown_caller) },
                    fontWeight = if (conversation.unread) FontWeight.Bold else null,
                )
            },
            supportingContent = {
                conversation.snippet?.let { snippet ->
                    Text(
                        text = snippet,
                        maxLines = 1,
                        fontWeight = if (conversation.unread) FontWeight.Bold else null,
                    )
                }
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(
                            conversation.timestampMillis,
                        ).toString(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (conversation.unread) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(10.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .testTag("unread"),
                        )
                    }
                }
            },
        )
    }
}
