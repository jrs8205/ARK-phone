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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.ContactDetails
import org.jarsi.arkphone.data.model.LabeledField
import org.jarsi.arkphone.ui.components.ContactAvatar
import org.jarsi.arkphone.ui.components.clickableListItemModifier
import org.jarsi.arkphone.ui.components.rememberHaptics

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
            )
        }
    }
}

@Composable
private fun ContactCardDetails(
    details: ContactDetails,
    modifier: Modifier,
    onCall: (String) -> Unit,
    onMessage: (String) -> Unit,
    onEmail: (String) -> Unit,
    onOpenAddress: (String) -> Unit,
    onOpenWebsite: (String) -> Unit,
    onOpenCallHistory: (String) -> Unit,
) {
    val haptics = rememberHaptics()
    val firstNumber = details.phones.firstOrNull()?.value
    Column(modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
        }
        Column(Modifier.fillMaxWidth()) {
            details.phones.forEach { field ->
                FieldRow(field, Icons.Filled.Call) { onCall(field.value) }
            }
            details.emails.forEach { field ->
                FieldRow(field, Icons.Outlined.Email) { onEmail(field.value) }
            }
            details.addresses.forEach { field ->
                FieldRow(field, Icons.Outlined.Place) { onOpenAddress(field.value) }
            }
            details.events.forEach { field ->
                FieldRow(field, Icons.Outlined.Cake, onTap = null)
            }
            details.organization?.let { organization ->
                FieldRow(LabeledField(organization, null), Icons.Outlined.Business, onTap = null)
            }
            details.note?.let { note ->
                FieldRow(LabeledField(note, null), Icons.Outlined.Notes, onTap = null)
            }
            details.websites.forEach { url ->
                FieldRow(LabeledField(url, null), Icons.Outlined.Language) { onOpenWebsite(url) }
            }
            if (firstNumber != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ListItem(
                    modifier = clickableListItemModifier { onOpenCallHistory(firstNumber) },
                    headlineContent = { Text(stringResource(R.string.contact_card_call_history)) },
                    leadingContent = {
                        Icon(Icons.Outlined.History, contentDescription = null)
                    },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FieldRow(field: LabeledField, icon: ImageVector, onTap: (() -> Unit)?) {
    ListItem(
        modifier = if (onTap != null) clickableListItemModifier(onTap) else Modifier,
        headlineContent = { Text(field.value) },
        supportingContent = field.label?.let { label -> { Text(label) } },
        leadingContent = { Icon(icon, contentDescription = null) },
    )
}
