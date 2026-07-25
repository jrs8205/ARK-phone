package org.jarsi.arkphone.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jarsi.arkphone.R
import org.jarsi.arkphone.ui.components.rememberHaptics

@Composable
fun OnboardingScreen(
    onRequestRole: () -> Unit,
    onRequestPermissions: () -> Unit,
    onDone: () -> Unit,
    isDefaultDialer: Boolean,
    hasPermissions: Boolean,
) {
    val haptics = rememberHaptics()
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.onboarding_body),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
            )
            Button(
                onClick = {
                    haptics.click()
                    onRequestRole()
                },
                enabled = !isDefaultDialer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isDefaultDialer) stringResource(R.string.onboarding_step_done)
                    else stringResource(R.string.onboarding_set_default),
                )
            }
            OutlinedButton(
                onClick = {
                    haptics.click()
                    onRequestPermissions()
                },
                // The call-log permissions are restricted and only granted to
                // the default dialer, so the role must come first.
                enabled = !hasPermissions && isDefaultDialer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    if (hasPermissions) stringResource(R.string.onboarding_step_done)
                    else stringResource(R.string.onboarding_grant_permissions),
                )
            }
            TextButton(
                onClick = {
                    haptics.click()
                    onDone()
                },
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(stringResource(R.string.onboarding_done))
            }
        }
    }
}
