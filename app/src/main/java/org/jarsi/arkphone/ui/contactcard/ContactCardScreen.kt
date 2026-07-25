package org.jarsi.arkphone.ui.contactcard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.ContactAppAction
import org.jarsi.arkphone.data.model.ContactDetails
import org.jarsi.arkphone.data.model.LabeledField
import org.jarsi.arkphone.ui.components.ContactAvatar
import org.jarsi.arkphone.ui.components.clickableListItemModifier
import org.jarsi.arkphone.ui.components.rememberHaptics
import org.jarsi.arkphone.ui.components.transparentListItemColors

@Composable
fun ContactCardScreen(
    viewModel: ContactCardViewModel,
    onBack: () -> Unit,
    onCall: (String) -> Unit,
    onMessage: (String) -> Unit,
    onEmail: (String) -> Unit,
    onOpenAddress: (String) -> Unit,
    onOpenWebsite: (String) -> Unit,
    onOpenCallHistory: (String) -> Unit,
    onOpenAppAction: (ContactAppAction) -> Unit,
    onShare: (ContactDetails) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ContactCardContent(
        uiState = uiState,
        onBack = onBack,
        onCall = onCall,
        onMessage = onMessage,
        onEmail = onEmail,
        onOpenAddress = onOpenAddress,
        onOpenWebsite = onOpenWebsite,
        onOpenCallHistory = onOpenCallHistory,
        onOpenAppAction = onOpenAppAction,
        onShare = { uiState.details?.let(onShare) },
        onToggleBlocked = viewModel::onToggleBlocked,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactCardContent(
    uiState: ContactCardUiState,
    onBack: () -> Unit,
    onCall: (String) -> Unit = {},
    onMessage: (String) -> Unit = {},
    onEmail: (String) -> Unit = {},
    onOpenAddress: (String) -> Unit = {},
    onOpenWebsite: (String) -> Unit = {},
    onOpenCallHistory: (String) -> Unit = {},
    onOpenAppAction: (ContactAppAction) -> Unit = {},
    onShare: () -> Unit = {},
    onToggleBlocked: () -> Unit = {},
) {
    val haptics = rememberHaptics()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptics.click()
                            onBack()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            uiState.loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            uiState.details == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.contact_card_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            else -> ContactCardDetails(
                details = uiState.details,
                blocked = uiState.blocked,
                canBlock = uiState.canBlock,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
                onCall = onCall,
                onMessage = onMessage,
                onEmail = onEmail,
                onOpenAddress = onOpenAddress,
                onOpenWebsite = onOpenWebsite,
                onOpenCallHistory = onOpenCallHistory,
                onOpenAppAction = onOpenAppAction,
                onShare = onShare,
                onToggleBlocked = onToggleBlocked,
            )
        }
    }
}

@Composable
private fun ContactCardDetails(
    details: ContactDetails,
    blocked: Boolean,
    canBlock: Boolean,
    modifier: Modifier,
    onCall: (String) -> Unit,
    onMessage: (String) -> Unit,
    onEmail: (String) -> Unit,
    onOpenAddress: (String) -> Unit,
    onOpenWebsite: (String) -> Unit,
    onOpenCallHistory: (String) -> Unit,
    onOpenAppAction: (ContactAppAction) -> Unit,
    onShare: () -> Unit,
    onToggleBlocked: () -> Unit,
) {
    val haptics = rememberHaptics()
    val firstNumber = details.phones.firstOrNull()?.value
    Column(
        modifier.padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ContactAvatar(
            displayName = details.displayName,
            photoUri = details.photoUri,
            size = 112.dp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text(details.displayName, style = MaterialTheme.typography.headlineSmall)
            if (details.starred) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.contact_card_favorite),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (firstNumber != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 16.dp),
            ) {
                Button(
                    onClick = {
                        haptics.confirm()
                        onCall(firstNumber)
                    },
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null)
                    Text(
                        stringResource(R.string.dialpad_call),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        haptics.click()
                        onMessage(firstNumber)
                    },
                ) {
                    Icon(Icons.Outlined.Message, contentDescription = null)
                    Text(
                        stringResource(R.string.detail_message),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
        SectionCard(details.phones.isNotEmpty()) {
            details.phones.forEach { field ->
                FieldRow(
                    field = field,
                    icon = Icons.Filled.Call,
                    onTap = { onCall(field.value) },
                    trailing = {
                        IconButton(
                            onClick = {
                                haptics.click()
                                onMessage(field.value)
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Message,
                                contentDescription = stringResource(R.string.detail_message),
                            )
                        }
                    },
                )
            }
        }
        SectionCard(details.emails.isNotEmpty()) {
            details.emails.forEach { field ->
                FieldRow(field, Icons.Outlined.Email, onTap = { onEmail(field.value) })
            }
        }
        SectionCard(details.addresses.isNotEmpty()) {
            details.addresses.forEach { field ->
                FieldRow(
                    field = field,
                    icon = Icons.Outlined.Place,
                    onTap = { onOpenAddress(field.value) },
                    trailing = {
                        IconButton(
                            onClick = {
                                haptics.click()
                                onOpenAddress(field.value)
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Directions,
                                contentDescription = stringResource(R.string.contact_card_directions),
                            )
                        }
                    },
                )
            }
        }
        SectionCard(details.events.isNotEmpty()) {
            details.events.forEach { field ->
                FieldRow(field, Icons.Outlined.Cake, onTap = null)
            }
        }
        SectionCard(
            details.organization != null || details.note != null || details.websites.isNotEmpty(),
        ) {
            details.organization?.let {
                FieldRow(LabeledField(it, null), Icons.Outlined.Business, onTap = null)
            }
            details.note?.let {
                FieldRow(LabeledField(it, null), Icons.Outlined.Notes, onTap = null)
            }
            details.websites.forEach { url ->
                FieldRow(
                    LabeledField(url, null),
                    Icons.Outlined.Language,
                    onTap = { onOpenWebsite(url) },
                )
            }
        }
        if (details.appActions.isNotEmpty()) {
            SectionTitle(stringResource(R.string.contact_card_connected_apps))
            SectionCard(true) {
                details.appActions.forEach { action ->
                    ListItem(
                        modifier = clickableListItemModifier { onOpenAppAction(action) },
                        colors = transparentListItemColors(),
                        headlineContent = { Text(action.label) },
                        leadingContent = { AppActionIcon(action) },
                    )
                }
            }
        }
        SectionCard(true) {
            details.lookupKey?.let {
                ListItem(
                    modifier = clickableListItemModifier(onShare),
                    colors = transparentListItemColors(),
                    headlineContent = { Text(stringResource(R.string.contact_card_share)) },
                    leadingContent = { Icon(Icons.Outlined.Share, contentDescription = null) },
                )
            }
            if (canBlock && details.phones.isNotEmpty()) {
                ListItem(
                    modifier = clickableListItemModifier(onToggleBlocked),
                    colors = transparentListItemColors(),
                    headlineContent = {
                        Text(
                            stringResource(
                                if (blocked) R.string.contact_card_unblock else R.string.contact_card_block,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
            if (firstNumber != null) {
                ListItem(
                    modifier = clickableListItemModifier { onOpenCallHistory(firstNumber) },
                    colors = transparentListItemColors(),
                    headlineContent = { Text(stringResource(R.string.contact_card_call_history)) },
                    leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AppActionIcon(action: ContactAppAction) {
    val context = LocalContext.current
    // The owning app's real launcher icon; nothing trademarked ships with us.
    val appIcon = remember(action.packageName) {
        action.packageName?.let { pkg ->
            runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
        }
    }
    when {
        appIcon != null -> AsyncImage(
            model = appIcon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        action.mimeType.contains("whatsapp") -> Icon(
            painterResource(R.drawable.ic_whatsapp),
            contentDescription = null,
            tint = Color.Unspecified,
        )
        else -> Icon(Icons.Outlined.Apps, contentDescription = null)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionCard(visible: Boolean, content: @Composable () -> Unit) {
    if (!visible) return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun FieldRow(
    field: LabeledField,
    icon: ImageVector,
    onTap: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = if (onTap != null) clickableListItemModifier(onTap) else Modifier,
        colors = transparentListItemColors(),
        headlineContent = { Text(field.value) },
        supportingContent = field.label?.let { label -> { Text(label) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = trailing,
    )
}
