package org.jarsi.arkphone.ui.messages

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.ui.components.ContactAvatar
import org.jarsi.arkphone.ui.components.RowCard
import org.jarsi.arkphone.ui.components.SearchRow
import org.jarsi.arkphone.ui.components.SelectionTopBar
import org.jarsi.arkphone.ui.components.clickableListItem
import org.jarsi.arkphone.ui.components.rememberHaptics
import org.jarsi.arkphone.ui.components.transparentListItemColors
import org.jarsi.arkphone.ui.settings.SettingsActivity

@Composable
fun MessagesScreen(
    onOpenThread: (Long) -> Unit,
    onNewMessage: () -> Unit,
    onRequestPermission: () -> Unit,
    onRequestSmsRole: () -> Unit = {},
) {
    val viewModel: MessagesViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
        onRequestSmsRole = onRequestSmsRole,
        onOpenSettings = { context.startActivity(SettingsActivity.intent(context)) },
        onToggleSelection = viewModel::onToggleSelection,
        onSelectAll = viewModel::onSelectAll,
        onClearSelection = viewModel::onClearSelection,
        onDeleteSelected = viewModel::onDeleteSelected,
    )
}

@Composable
fun MessagesContent(
    uiState: MessagesUiState,
    onQueryChange: (String) -> Unit,
    onOpenThread: (Long) -> Unit,
    onNewMessage: () -> Unit,
    onRequestPermission: () -> Unit,
    onRequestSmsRole: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onToggleSelection: (Long) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
) {
    val haptics = rememberHaptics()
    var confirmBulkDelete by remember { mutableStateOf(false) }
    BackHandler(enabled = uiState.selectionActive) { onClearSelection() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (uiState.selectionActive) {
                // The main scaffold already pads the status bar above this column.
                SelectionTopBar(
                    count = uiState.selectedThreadIds.size,
                    onClose = onClearSelection,
                    windowInsets = WindowInsets(0.dp),
                ) {
                    IconButton(
                        onClick = {
                            haptics.click()
                            onSelectAll()
                        },
                    ) {
                        Icon(
                            Icons.Filled.SelectAll,
                            contentDescription = stringResource(R.string.selection_select_all),
                        )
                    }
                    IconButton(
                        onClick = {
                            haptics.click()
                            confirmBulkDelete = true
                        },
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.common_delete),
                        )
                    }
                }
            } else {
                SearchRow(
                    query = uiState.query,
                    onQueryChange = onQueryChange,
                    placeholder = stringResource(R.string.messages_search_placeholder),
                    onOpenSettings = onOpenSettings,
                )
            }
            if (!uiState.loading && !uiState.isDefaultSmsApp) {
                SmsRoleBanner(onRequestSmsRole)
            }
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
                        val threadId = item.conversation.threadId
                        ConversationRow(
                            item = item,
                            selectionActive = uiState.selectionActive,
                            selected = threadId in uiState.selectedThreadIds,
                            onClick = {
                                if (uiState.selectionActive) {
                                    onToggleSelection(threadId)
                                } else {
                                    onOpenThread(threadId)
                                }
                            },
                            onLongPress = { onToggleSelection(threadId) },
                        )
                    }
                }
            }
        }
        if (!uiState.selectionActive) {
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
    if (confirmBulkDelete) {
        val count = uiState.selectedThreadIds.size
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = {
                Text(pluralStringResource(R.plurals.conversations_delete_confirm_title, count, count))
            },
            text = {
                Text(pluralStringResource(R.plurals.conversations_delete_confirm_text, count, count))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmBulkDelete = false
                        onDeleteSelected()
                    },
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun SmsRoleBanner(onRequestSmsRole: () -> Unit) {
    val haptics = rememberHaptics()
    // Shaped and inset like the conversation cards below it so the banner
    // reads as part of the list instead of a strip glued to the search bar.
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.messages_banner_not_default),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    haptics.click()
                    onRequestSmsRole()
                },
            ) {
                Text(stringResource(R.string.messages_banner_set_default))
            }
        }
    }
}

@Composable
private fun ConversationRow(
    item: ConversationItem,
    selectionActive: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val conversation = item.conversation
    RowCard(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        ListItem(
            colors = transparentListItemColors(),
            modifier = Modifier.clickableListItem(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectionActive) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .testTag("select_conversation"),
                        )
                    }
                    ContactAvatar(
                        displayName = item.title.ifBlank { null },
                        photoUri = item.photoUri,
                    )
                }
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
