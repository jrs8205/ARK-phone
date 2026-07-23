package org.jarsi.arkphone.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jarsi.arkphone.R
import org.jarsi.arkphone.ui.contacts.ContactsScreen
import org.jarsi.arkphone.ui.recents.RecentsScreen

enum class MainTab { RECENTS, CONTACTS }

@Composable
fun MainScreen(
    onCall: (String) -> Unit,
    onOpenDialpad: () -> Unit,
    onRequestPermissions: () -> Unit,
    showDefaultDialerBanner: Boolean,
    onRequestDefaultDialer: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.RECENTS) }
    Scaffold(
        bottomBar = { ArkBottomBar(selected = selectedTab, onSelect = { selectedTab = it }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onOpenDialpad) {
                Icon(Icons.Filled.Dialpad, contentDescription = stringResource(R.string.open_dialpad))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (showDefaultDialerBanner) {
                DefaultDialerBanner(onRequestDefaultDialer)
            }
            when (selectedTab) {
                MainTab.RECENTS -> RecentsScreen(onCall = onCall, onRequestPermissions = onRequestPermissions)
                MainTab.CONTACTS -> ContactsScreen(onCall = onCall, onRequestPermissions = onRequestPermissions)
            }
        }
    }
}

@Composable
fun ArkBottomBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == MainTab.RECENTS,
            onClick = { onSelect(MainTab.RECENTS) },
            icon = { Icon(Icons.Filled.History, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_recents)) },
        )
        NavigationBarItem(
            selected = selected == MainTab.CONTACTS,
            onClick = { onSelect(MainTab.CONTACTS) },
            icon = { Icon(Icons.Filled.Contacts, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_contacts)) },
        )
    }
}

@Composable
private fun DefaultDialerBanner(onRequestDefaultDialer: () -> Unit) {
    Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.banner_not_default),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRequestDefaultDialer) {
                Text(stringResource(R.string.banner_set_default))
            }
        }
    }
}
