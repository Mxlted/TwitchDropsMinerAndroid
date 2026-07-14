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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.ui.theme.AppMuted

@Composable
fun SettingsScreen(
    settings: AppSettings,
    miningActive: Boolean,
    onRunInForegroundChanged: (Boolean) -> Unit,
    onWatchIntervalChanged: (Int) -> Unit,
    onInventoryRefreshChanged: (Int) -> Unit,
    onKeepActiveScreenModeChanged: (Boolean) -> Unit,
    onFallbackToOtherGamesChanged: (Boolean) -> Unit,
    onAdvancedBackendModeChanged: (Boolean) -> Unit,
    onSaveBackendUrl: (String) -> Unit,
    onDebugLoggingChanged: (Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onResetSettings: () -> Unit,
    onResetSession: () -> Unit,
) {
    var backendUrl by remember { mutableStateOf(settings.backendUrl) }
    var watchInterval by remember { mutableFloatStateOf(settings.watchIntervalSeconds.toFloat()) }
    var inventoryRefresh by remember { mutableFloatStateOf(settings.inventoryRefreshMinutes.toFloat()) }
    var confirmSettingsReset by remember { mutableStateOf(false) }
    var confirmSessionReset by remember { mutableStateOf(false) }

    if (confirmSettingsReset) {
        AlertDialog(
            onDismissRequest = { confirmSettingsReset = false },
            title = { Text("Reset app settings?") },
            text = {
                Text("This restores runtime preferences, priorities, exclusions, and debug options to defaults. Your Twitch login stays signed in.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmSettingsReset = false
                        onResetSettings()
                    },
                ) {
                    Text("Reset Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSettingsReset = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (confirmSessionReset) {
        AlertDialog(
            onDismissRequest = { confirmSessionReset = false },
            title = { Text("Reset Twitch session?") },
            text = {
                Text("This signs out, stops mining, and clears saved priorities and campaign exclusions on this device.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmSessionReset = false
                        onResetSession()
                    },
                ) {
                    Text("Reset Session")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSessionReset = false }) {
                    Text("Cancel")
                }
            },
        )
    }

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
        ScreenHeader(
            title = "Settings",
            subtitle = "Runtime preferences and local-only controls",
        )

        SectionCard {
            SectionTitle("Local Runtime", "Timing and foreground-service behavior.")
            ToggleRow(
                title = "Foreground service while mining",
                subtitle = if (miningActive) {
                    "Stop mining before changing how the runtime is hosted."
                } else {
                    "Shows a persistent notification so Android treats mining as active work."
                },
                checked = settings.runInForeground,
                onCheckedChange = onRunInForegroundChanged,
                enabled = !miningActive,
            )
            SettingSlider(
                title = "Watch heartbeat interval",
                valueLabel = "${watchInterval.toInt()} seconds",
                helper = "How often the local runtime sends watch/progress activity while mining.",
                value = watchInterval,
                onValueChange = { watchInterval = it },
                onValueChangeFinished = { onWatchIntervalChanged(watchInterval.toInt()) },
                valueRange = 20f..300f,
                steps = 13,
            )
            SettingSlider(
                title = "Inventory refresh interval",
                valueLabel = "${inventoryRefresh.toInt()} minutes",
                helper = "How often campaigns and drop progress are reloaded from Twitch.",
                value = inventoryRefresh,
                onValueChange = { inventoryRefresh = it },
                onValueChangeFinished = { onInventoryRefreshChanged(inventoryRefresh.toInt()) },
                valueRange = 15f..180f,
                steps = 10,
            )
        }

        SectionCard {
            SectionTitle("Keep Active Screen", "Optional display-awake mode for active sessions.")
            ToggleRow(
                title = "Enable dim keep-active screen",
                subtitle = "Adds a mostly black tap-to-return screen and keeps the display awake.",
                checked = settings.keepActiveScreenMode,
                onCheckedChange = onKeepActiveScreenModeChanged,
            )
            Text(
                text = "This can use more battery. It does not change mining selection or claiming behavior.",
                color = AppMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard {
            SectionTitle(
                title = "Game Fallback",
                subtitle = "Use one ordered fallback path when preferred work is unavailable.",
            )
            ToggleRow(
                title = "Fallback to other games",
                subtitle = "Priority games first, then linked/unlinked campaigns with claimed drops, linked/unlinked campaigns with viewing progress, and finally fresh linked/unlinked games.",
                checked = settings.fallbackToOtherGames,
                onCheckedChange = onFallbackToOtherGamesChanged,
            )
        }

        SectionCard {
            SectionTitle("Reliability", "Android power-management controls.")
            OutlinedButton(onClick = onOpenBatterySettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open Battery Settings")
            }
        }

        SectionCard {
            SectionTitle("Advanced", "Optional backend comparison tools for development.")
            ToggleRow(
                title = "Show optional backend URL",
                subtitle = "Reserved for comparing against a local PC/backend. Normal Android mining does not need it.",
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
            SectionTitle("Debug", "Local diagnostics and reset controls.")
            ToggleRow(
                title = "Verbose local logs",
                subtitle = "Record additional local messages for troubleshooting long runs.",
                checked = settings.debugLogging,
                onCheckedChange = onDebugLoggingChanged,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { confirmSettingsReset = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !miningActive,
                ) {
                    Text("Reset Settings")
                }
                OutlinedButton(
                    onClick = { confirmSessionReset = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset Twitch Session")
                }
            }
            if (miningActive) {
                Text(
                    text = "Stop mining before resetting app settings. Twitch Session reset can stop mining and sign out directly.",
                    color = AppMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    valueLabel: String,
    helper: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = AppMuted,
            )
        }
        Text(
            text = helper,
            style = MaterialTheme.typography.bodySmall,
            color = AppMuted,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
        )
    }
}
