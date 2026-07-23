package org.jarsi.arkphone.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.ui.components.ContactAvatar
import org.jarsi.arkphone.ui.contacts.ContactsViewModel
import org.jarsi.arkphone.ui.recents.RecentsContent
import org.jarsi.arkphone.ui.recents.RecentsUiState
import org.jarsi.arkphone.ui.recents.RecentsViewModel

@Composable
fun HomeScreen(
    onCall: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    recentsViewModel: RecentsViewModel = hiltViewModel(),
    // Own instance: sharing the contacts tab's ViewModel would filter the
    // favorites row by whatever search query is active on that tab.
    contactsViewModel: ContactsViewModel = hiltViewModel(key = "home-favorites"),
) {
    val recentsUiState by recentsViewModel.uiState.collectAsStateWithLifecycle()
    val contactsUiState by contactsViewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        recentsViewModel.refreshPermissionState()
        contactsViewModel.refreshPermissionState()
        onPauseOrDispose { }
    }
    HomeContent(
        favorites = contactsUiState.favorites,
        recentsUiState = recentsUiState,
        onCall = onCall,
        onRequestPermissions = onRequestPermissions,
    )
}

@Composable
fun HomeContent(
    favorites: List<Contact>,
    recentsUiState: RecentsUiState,
    onCall: (String) -> Unit,
    onRequestPermissions: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (favorites.isNotEmpty()) {
            Text(
                text = stringResource(R.string.contacts_favorites),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(favorites, key = { it.id }) { contact ->
                    FavoriteItem(contact = contact, onCall = onCall)
                }
            }
            Text(
                text = stringResource(R.string.tab_recents),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            RecentsContent(
                uiState = recentsUiState,
                onCall = onCall,
                onRequestPermissions = onRequestPermissions,
            )
        }
    }
}

@Composable
private fun FavoriteItem(contact: Contact, onCall: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(72.dp)
            .clickable(enabled = contact.phoneNumber != null) {
                contact.phoneNumber?.let(onCall)
            }
            .padding(4.dp),
    ) {
        ContactAvatar(contact = contact, size = 56.dp)
        Text(
            text = contact.displayName,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
