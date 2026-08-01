package org.jarsi.arkphone.ui.conversation

import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import androidx.compose.ui.res.pluralStringResource
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.data.model.MessageStatus
import org.jarsi.arkphone.ui.components.ContactAvatar
import org.jarsi.arkphone.ui.components.SelectionTopBar

@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel,
    onBack: () -> Unit,
    onCall: (String) -> Unit,
    onOpenContact: (Long) -> Unit,
    onRetryDownload: (Long) -> Unit = {},
    initialComposerText: String = "",
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.id) {
        if (uiState.messages.isNotEmpty()) viewModel.onMessagesViewed()
    }
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
        onRetryDownload = onRetryDownload,
        onPickImage = {
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onRemoveAttachment = { viewModel.onAttachImage(null) },
        onToggleMessageSelection = viewModel::onToggleMessageSelection,
        onClearSelection = viewModel::onClearSelection,
        onDeleteSelected = viewModel::onDeleteSelected,
        onShareSelected = {
            viewModel.onShareSelected { intent ->
                context.startActivity(Intent.createChooser(intent, null))
            }
        },
        onCallNumber = onCall,
        onOpenLink = { url ->
            val action =
                if (url.startsWith("mailto:")) Intent.ACTION_SENDTO else Intent.ACTION_VIEW
            runCatching { context.startActivity(Intent(action, url.toUri())) }
        },
        initialComposerText = initialComposerText,
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
    onRetryDownload: (Long) -> Unit = {},
    onPickImage: () -> Unit = {},
    onRemoveAttachment: () -> Unit = {},
    onToggleMessageSelection: (Message) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onShareSelected: () -> Unit = {},
    onCallNumber: (String) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    initialComposerText: String = "",
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var viewerImageUri by remember { mutableStateOf<String?>(null) }
    var linkDialogNumber by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val rows = uiState.rows
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
    }
    BackHandler(enabled = uiState.selectionActive) { onClearSelection() }
    Scaffold(
        topBar = {
            if (uiState.selectionActive) {
                SelectionTopBar(
                    count = uiState.selectedMessageKeys.size,
                    onClose = onClearSelection,
                ) {
                    IconButton(onClick = onShareSelected) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.selection_share),
                        )
                    }
                    IconButton(onClick = { confirmBulkDelete = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.common_delete),
                        )
                    }
                }
            } else {
                ConversationTopBar(
                    uiState = uiState,
                    menuOpen = menuOpen,
                    onMenuOpenChange = { menuOpen = it },
                    onBack = onBack,
                    onCall = onCall,
                    onOpenContact = onOpenContact,
                    onToggleBlocked = onToggleBlocked,
                    onDeleteConversation = { confirmDelete = true },
                )
            }
        },
        bottomBar = {
            ComposerRow(
                enabled = (uiState.address != null || uiState.isGroup) && uiState.canSend,
                attachedImageUri = uiState.attachedImageUri,
                onSendText = onSendText,
                onPickImage = onPickImage,
                onRemoveAttachment = onRemoveAttachment,
                initialText = initialComposerText,
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
                        selectionActive = uiState.selectionActive,
                        selected = row.message.selectionKey in uiState.selectedMessageKeys,
                        onToggle = onToggleMessageSelection,
                        onRetryDownload = onRetryDownload,
                        onOpenImage = { viewerImageUri = it },
                        onLinkTap = { url ->
                            if (url.startsWith("tel:")) {
                                linkDialogNumber = url.removePrefix("tel:")
                            } else {
                                onOpenLink(url)
                            }
                        },
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
    linkDialogNumber?.let { number ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { linkDialogNumber = null },
            title = { Text(number) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(number))
                        linkDialogNumber = null
                    },
                ) { Text(stringResource(R.string.message_link_copy)) }
                TextButton(
                    onClick = {
                        onCallNumber(number)
                        linkDialogNumber = null
                    },
                ) { Text(stringResource(R.string.message_link_call)) }
            },
            dismissButton = {
                TextButton(onClick = { linkDialogNumber = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (confirmBulkDelete) {
        val count = uiState.selectedMessageKeys.size
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = {
                Text(pluralStringResource(R.plurals.messages_delete_confirm_title, count, count))
            },
            text = {
                Text(pluralStringResource(R.plurals.messages_delete_confirm_text, count, count))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationTopBar(
    uiState: ConversationUiState,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onOpenContact: () -> Unit,
    onToggleBlocked: () -> Unit,
    onDeleteConversation: () -> Unit,
) {
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
            IconButton(onClick = { onMenuOpenChange(true) }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.conversation_menu),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                if (uiState.address != null) {
                    val clipboard = LocalClipboardManager.current
                    val context = LocalContext.current
                    val copiedText = stringResource(R.string.number_copied)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.conversation_copy_number)) },
                        onClick = {
                            onMenuOpenChange(false)
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
                            onMenuOpenChange(false)
                            onToggleBlocked()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.conversation_delete)) },
                    onClick = {
                        onMenuOpenChange(false)
                        onDeleteConversation()
                    },
                )
            }
        },
    )
}

@Composable
private fun ComposerRow(
    enabled: Boolean,
    attachedImageUri: String?,
    onSendText: (String) -> Unit,
    onPickImage: () -> Unit,
    onRemoveAttachment: () -> Unit,
    initialText: String = "",
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
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
    selectionActive: Boolean = false,
    selected: Boolean = false,
    onToggle: (Message) -> Unit = {},
    onRetryDownload: (Long) -> Unit = {},
    onOpenImage: (String) -> Unit = {},
    onLinkTap: (String) -> Unit = {},
) {
    val incoming = message.incoming
    val failed = !incoming && message.status == MessageStatus.FAILED
    val bubbleClick: () -> Unit = when {
        selectionActive -> {
            { onToggle(message) }
        }
        message.pendingDownload -> {
            { onRetryDownload(message.id) }
        }
        // The retry path only exists for SMS rows.
        failed && !message.isMms -> {
            { onRetry(message) }
        }
        else -> {
            {}
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionActive) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle(message) },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .testTag("select_message"),
            )
        }
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
                        onClick = bubbleClick,
                        onLongClick = { onToggle(message) },
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
                                .combinedClickable(
                                    onClick = {
                                        if (selectionActive) {
                                            onToggle(message)
                                        } else {
                                            onOpenImage(attachment.partUri)
                                        }
                                    },
                                    onLongClick = { onToggle(message) },
                                )
                                .testTag("mms_image"),
                        )
                    }
                    val bodyText = when {
                        message.pendingDownload ->
                            stringResource(R.string.mms_download_failed_retry)
                        else -> message.body.orEmpty()
                    }
                    if (bodyText.isNotEmpty() || message.attachments.isEmpty()) {
                        val linksEnabled = !selectionActive && !message.pendingDownload
                        val links = remember(bodyText, linksEnabled) {
                            if (linksEnabled) MessageLinkifier.detect(bodyText) else emptyList()
                        }
                        val linkColor =
                            if (incoming) MaterialTheme.colorScheme.primary else Color.Unspecified
                        val body = remember(bodyText, links, linkColor) {
                            styledMessageBody(
                                bodyText,
                                links,
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            )
                        }
                        val currentOnLinkTap by rememberUpdatedState(onLinkTap)
                        val currentBubbleClick by rememberUpdatedState(bubbleClick)
                        val currentOnToggle by rememberUpdatedState(onToggle)
                        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyLarge,
                            onTextLayout = { layout = it },
                            modifier = if (links.isEmpty()) {
                                Modifier
                            } else {
                                Modifier
                                    .pointerInput(links) {
                                        detectTapGestures(
                                            onTap = { position ->
                                                val link =
                                                    layout?.let { linkAt(it, links, position) }
                                                if (link != null) {
                                                    currentOnLinkTap(link.url)
                                                } else {
                                                    currentBubbleClick()
                                                }
                                            },
                                            onLongPress = { currentOnToggle(message) },
                                        )
                                    }
                                    // The custom tap path carries no semantics of its
                                    // own; expose each link so TalkBack and Switch
                                    // Access can activate it too.
                                    .semantics {
                                        customActions = links.map { link ->
                                            CustomAccessibilityAction(
                                                bodyText.substring(link.start, link.end),
                                            ) {
                                                currentOnLinkTap(link.url)
                                                true
                                            }
                                        }
                                    }
                            },
                        )
                    }
                }
            }
            val statusText = when {
                failed -> stringResource(
                    if (message.isMms) {
                        R.string.message_status_failed
                    } else {
                        R.string.message_status_failed_retry
                    },
                )
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
