package com.nathan.twitchdropsminer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nathan.twitchdropsminer.android.ui.theme.AppMuted

@Composable
fun OnboardingScreen(
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Twitch Drops Miner",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "A self-contained Android runtime for discovering, watching, tracking, and claiming Twitch Drops where mobile constraints allow it.",
            style = MaterialTheme.typography.bodyLarge,
            color = AppMuted,
        )

        SectionCard {
            SectionTitle("What It Does")
            Text("Runs a local Android mining workflow modeled after the original Python app.")
            Text("Loads drops inventory, lets you select campaigns, finds eligible live channels, sends watch activity, tracks progress, and attempts claims.")
        }

        SectionCard {
            SectionTitle("Login And Session")
            Text("Use Twitch device-code login. The app never asks for your Twitch password.")
            Text("OAuth tokens are stored with Android encrypted preferences and can be reset from Settings.")
        }

        SectionCard {
            SectionTitle("Android Runtime Limits")
            Text("Active mining should run as a foreground service with a persistent notification.")
            Text("Battery optimization, network changes, notification permission, and Twitch endpoint changes can interrupt background work.")
        }

        SectionCard {
            SectionTitle("Keep Active Screen Mode")
            Text("You can enable a mostly black screen that keeps the app active until you tap to return.")
            Text("Use it only when you intentionally want the device awake.")
        }

        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Continue to Setup")
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(text = subtitle, color = AppMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
