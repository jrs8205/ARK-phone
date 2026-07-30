package org.jarsi.arkphone.ui.conversation

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.data.model.MessageStatus
import org.jarsi.arkphone.ui.components.ContactAvatar

@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel,
    onBack: () -> Unit,
    onCall: (String) -> Unit,
    onOpenContact: (Long) -> Unit,
    onRetryDownload: (Long) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::onAttachImage) }
    ConversationContent(
        uiState = uiState,
        onBack = onBack,
        onCall = { uiState.address?.let(onCall) },
        onOpenContact = { uiState.contactId?.let(onOpenContact) },
        onToggleBlocked = viewModel::onToggleBlocked,
        onDeleteConversation = { viewModel.onDeleteConversation(onBack) },
        onSendText = viewModel::onSendText,
        onRetry = viewModel::onRetry,
        onDeleteMessage = viewModel::onDeleteMessage,
        onRetryDownload = onRetryDownload,
        onPickImage = {
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onRemoveAttachment = { viewModel.onAttachImage(null) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationContent(
    uiState: ConversationUiState,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onOpenContact: () -> Unit,
    onToggleBlocked: () -> Unit,
    onDeleteConversation: () -> Unit,
    onSendText: (String) -> Unit = {},
    onRetry: (Message) -> Unit = {},
    onDeleteMessage: (Message) -> Unit = {},
    onRetryDownload: (Long) -> Unit = {},
    onPickImage: () -> Unit = {},
    onRemoveAttachment: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<Message?>(null) }
    var viewerImageUri by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val rows = uiState.rows
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
    }
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
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (uiState.contactId != null) {
                            Modifier.clickable(onClick = onOpenContact)
                        } else {
                            Modifier
                        },
                    ) {
                        ContactAvatar(
                            displayName = uiState.title.ifBlank { null },
                            photoUri = uiState.photoUri,
                            size = 36.dp,
                        )
                        Text(
                            text = uiState.title.ifBlank {
                                stringResource(R.string.call_log_unknown_caller)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                },
                actions = {
                    if (uiState.address != null) {
                        IconButton(onClick = onCall) {
                            Icon(
                                Icons.Filled.Call,
                                contentDescription = stringResource(R.string.conversation_call),
                            )
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.conversation_menu),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (uiState.address != null) {
                            val clipboard = LocalClipboardManager.current
                            val context = LocalContext.current
                            val copiedText = stringResource(R.string.number_copied)
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_copy_number)) },
                                onClick = {
                                    menuOpen = false
                                    clipboard.setText(AnnotatedString(uiState.address))
                                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                        if (uiState.address != null && uiState.canBlock) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (uiState.blocked) {
                                                R.string.detail_unblock
                                            } else {
                                                R.string.detail_block
                                            },
                                        ),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onToggleBlocked()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.conversation_delete)) },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            ComposerRow(
                enabled = (uiState.address != null || uiState.isGroup) && uiState.canSend,
                attachedImageUri = uiState.attachedImageUri,
                onSendText = onSendText,
                onPickImage = onPickImage,
                onRemoveAttachment = onRemoveAttachment,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(
                rows,
                key = { row ->
                    when (row) {
                        is ConversationRow.DaySeparator -> "day-${row.epochMillis}"
                        is ConversationRow.MessageRow -> "msg-${row.message.id}"
                    }
                },
            ) { row ->
                when (row) {
                    is ConversationRow.DaySeparator -> DaySeparatorRow(row.epochMillis)
                    is ConversationRow.MessageRow -> MessageBubble(
                        message = row.message,
                        onRetry = onRetry,
                        onLongPress = { deleteCandidate = it },
                        onRetryDownload = onRetryDownload,
                        onOpenImage = { viewerImageUri = it },
                    )
                }
            }
        }
    }
    viewerImageUri?.let { imageUri ->
        Dialog(onDismissRequest = { viewerImageUri = null }) {
            AsyncImage(
                model = imageUri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewerImageUri = null }
                    .testTag("mms_image_viewer"),
            )
        }
    }
    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.message_delete_confirm_title)) },
            text = { Text(stringResource(R.string.message_delete_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteCandidate = null
                        onDeleteMessage(candidate)
                    },
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.conversation_delete_confirm_title)) },
            text = { Text(stringResource(R.string.conversation_delete_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeleteConversation()
                    },
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ComposerRow(
    enabled: Boolean,
    attachedImageUri: String?,
    onSendText: (String) -> Unit,
    onPickImage: () -> Unit,
    onRemoveAttachment: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (attachedImageUri != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = attachedImageUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .sizeIn(maxWidth = 96.dp, maxHeight = 96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .testTag("attachment_preview"),
                )
                IconButton(
                    onClick = onRemoveAttachment,
                    modifier = Modifier.testTag("attachment_remove"),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.message_remove_attachment),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            IconButton(
                onClick = onPickImage,
                enabled = enabled,
                modifier = Modifier.testTag("composer_attach"),
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = stringResource(R.string.message_attach),
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                placeholder = { Text(stringResource(R.string.message_compose_hint)) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 5,
                modifier = Modifier
                    .weight(1f)
                    .testTag("composer_input"),
            )
            IconButton(
                onClick = {
                    onSendText(text)
                    text = ""
                },
                enabled = enabled && (text.isNotBlank() || attachedImageUri != null),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .testTag("composer_send"),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.message_send),
                )
            }
        }
    }
}

@Composable
private fun DaySeparatorRow(epochMillis: Long) {
    val context = LocalContext.current
    Text(
        text = DateUtils.formatDateTime(
            context,
            epochMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .testTag("day_separator"),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    onRetry: (Message) -> Unit,
    onLongPress: (Message) -> Unit = {},
    onRetryDownload: (Long) -> Unit = {},
    onOpenImage: (String) -> Unit = {},
) {
    val incoming = message.incoming
    val failed = !incoming && message.status == MessageStatus.FAILED
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        if (!incoming) Spacer(Modifier.weight(0.2f))
        Column(
            modifier = Modifier.weight(0.8f, fill = false),
            horizontalAlignment = if (incoming) Alignment.Start else Alignment.End,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (incoming) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                modifier = Modifier
                    .testTag(if (incoming) "bubble_in" else "bubble_out")
                    .combinedClickable(
                        onClick = when {
                            message.pendingDownload -> {
                                { onRetryDownload(message.id) }
                            }
                            failed -> {
                                { onRetry(message) }
                            }
                            else -> {
                                {}
                            }
                        },
                        onLongClick = { onLongPress(message) },
                    ),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    message.attachments.forEach { attachment ->
                        AsyncImage(
                            model = attachment.partUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .sizeIn(maxWidth = 240.dp, maxHeight = 240.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenImage(attachment.partUri) }
                                .testTag("mms_image"),
                        )
                    }
                    val bodyText = when {
                        message.pendingDownload ->
                            stringResource(R.string.mms_download_failed_retry)
                        else -> message.body.orEmpty()
                    }
                    if (bodyText.isNotEmpty() || message.attachments.isEmpty()) {
                        Text(
                            text = bodyText,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            val statusText = when {
                failed -> stringResource(R.string.message_status_failed_retry)
                !incoming -> when (message.status) {
                    MessageStatus.SENDING -> stringResource(R.string.message_status_sending)
                    MessageStatus.SENT -> stringResource(R.string.message_status_sent)
                    MessageStatus.DELIVERED -> stringResource(R.string.message_status_delivered)
                    else -> null
                }
                else -> null
            }
            if (statusText != null) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
        if (incoming) Spacer(Modifier.weight(0.2f))
    }
}
