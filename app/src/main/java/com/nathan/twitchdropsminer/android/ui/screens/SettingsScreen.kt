package com.nathan.twitchdropsminer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.ui.theme.AppMuted

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onRunInForegroundChanged: (Boolean) -> Unit,
    onWatchIntervalChanged: (Int) -> Unit,
    onInventoryRefreshChanged: (Int) -> Unit,
    onKeepActiveScreenModeChanged: (Boolean) -> Unit,
    onSampleFallbackChanged: (Boolean) -> Unit,
    onFallbackToAutoWhenPrioritizedCompleteChanged: (Boolean) -> Unit,
    onFallbackToAutoWhenNoPrioritizedChannelChanged: (Boolean) -> Unit,
    onAllowWatchingUnlinkedGamesChanged: (Boolean) -> Unit,
    onAdvancedBackendModeChanged: (Boolean) -> Unit,
    onSaveBackendUrl: (String) -> Unit,
    onDebugLoggingChanged: (Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onResetSession: () -> Unit,
) {
    var backendUrl by remember { mutableStateOf(settings.backendUrl) }
    var watchInterval by remember { mutableFloatStateOf(settings.watchIntervalSeconds.toFloat()) }
    var inventoryRefresh by remember { mutableFloatStateOf(settings.inventoryRefreshMinutes.toFloat()) }

    LaunchedEffect(settings.backendUrl) {
        backendUrl = settings.backendUrl
    }
    LaunchedEffect(settings.watchIntervalSeconds) {
        watchInterval = settings.watchIntervalSeconds.toFloat()
    }
    LaunchedEffect(settings.inventoryRefreshMinutes) {
        inventoryRefresh = settings.inventoryRefreshMinutes.toFloat()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        SectionCard {
            SectionTitle("Local Runtime")
            ToggleRow(
                title = "Run in foreground service",
                subtitle = "Use a persistent notification while mining.",
                checked = settings.runInForeground,
                onCheckedChange = onRunInForegroundChanged,
            )
            Text("Watch interval: ${watchInterval.toInt()} seconds", color = AppMuted)
            Slider(
                value = watchInterval,
                onValueChange = { watchInterval = it },
                onValueChangeFinished = { onWatchIntervalChanged(watchInterval.toInt()) },
                valueRange = 20f..180f,
                steps = 15,
            )
            Text("Inventory refresh: ${inventoryRefresh.toInt()} minutes", color = AppMuted)
            Slider(
                value = inventoryRefresh,
                onValueChange = { inventoryRefresh = it },
                onValueChangeFinished = { onInventoryRefreshChanged(inventoryRefresh.toInt()) },
                valueRange = 15f..180f,
                steps = 10,
            )
        }

        SectionCard {
            SectionTitle("Keep Active Screen")
            ToggleRow(
                title = "Keep active screen mode",
                subtitle = "Allow a mostly black tap-to-return screen and keep the display awake.",
                checked = settings.keepActiveScreenMode,
                onCheckedChange = onKeepActiveScreenModeChanged,
            )
            Text(
                text = "This can use more battery. Enable it only when you intentionally keep the app active.",
                color = AppMuted,
            )
        }

        SectionCard {
            SectionTitle("Game Priority Fallbacks")
            ToggleRow(
                title = "Fallback when prioritized games are complete",
                subtitle = "Switch to Auto Mode for other eligible games after every prioritized game is complete.",
                checked = settings.fallbackToAutoWhenPrioritizedComplete,
                onCheckedChange = onFallbackToAutoWhenPrioritizedCompleteChanged,
            )
            ToggleRow(
                title = "Fallback when prioritized games have no live channel",
                subtitle = "Switch to Auto Mode when prioritized games have work but no eligible live channel.",
                checked = settings.fallbackToAutoWhenNoPrioritizedChannel,
                onCheckedChange = onFallbackToAutoWhenNoPrioritizedChannelChanged,
            )
            ToggleRow(
                title = "Allow watching unlinked games",
                subtitle = "After prioritized and linked games are tried, watch unlinked games only if Twitch progress increases.",
                checked = settings.allowWatchingUnlinkedGames,
                onCheckedChange = onAllowWatchingUnlinkedGamesChanged,
            )
        }

        SectionCard {
            SectionTitle("Reliability")
            ToggleRow(
                title = "Development sample data",
                subtitle = "Use local sample campaigns/channels only for explicit demo or endpoint testing.",
                checked = settings.useSampleDataFallback,
                onCheckedChange = onSampleFallbackChanged,
            )
            OutlinedButton(onClick = onOpenBatterySettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open Battery Settings")
            }
        }

        SectionCard {
            SectionTitle("Advanced")
            ToggleRow(
                title = "Optional backend debug mode",
                subtitle = "Reserved for comparing against a local PC/backend. Not required for normal use.",
                checked = settings.advancedBackendMode,
                onCheckedChange = onAdvancedBackendModeChanged,
            )
            if (settings.advancedBackendMode) {
                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = { backendUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Optional backend URL") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Button(
                    onClick = { onSaveBackendUrl(backendUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Debug URL")
                }
            }
        }

        SectionCard {
            SectionTitle("Debug")
            ToggleRow(
                title = "Verbose local logs",
                checked = settings.debugLogging,
                onCheckedChange = onDebugLoggingChanged,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onResetSession, modifier = Modifier.weight(1f)) {
                    Text("Reset Session")
                }
            }
        }
    }
}
