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
        ScreenHeader(
            title = "Settings",
            subtitle = "Runtime preferences and local-only controls",
        )

        SectionCard {
            SectionTitle("Local Runtime", "Timing and foreground-service behavior.")
            ToggleRow(
                title = "Foreground service while mining",
                subtitle = "Shows a persistent notification so Android treats mining as active work.",
                checked = settings.runInForeground,
                onCheckedChange = onRunInForegroundChanged,
            )
            SettingSlider(
                title = "Watch heartbeat interval",
                valueLabel = "${watchInterval.toInt()} seconds",
                helper = "How often the local runtime sends watch/progress activity while mining.",
                value = watchInterval,
                onValueChange = { watchInterval = it },
                onValueChangeFinished = { onWatchIntervalChanged(watchInterval.toInt()) },
                valueRange = 20f..180f,
                steps = 15,
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
                title = "Game Priority Fallbacks",
                subtitle = "Priority remains strict unless one of these fallbacks is enabled.",
            )
            ToggleRow(
                title = "Use Auto Mode after priority completes",
                subtitle = "Switch to Auto Mode for other eligible games after every prioritized game is complete.",
                checked = settings.fallbackToAutoWhenPrioritizedComplete,
                onCheckedChange = onFallbackToAutoWhenPrioritizedCompleteChanged,
            )
            ToggleRow(
                title = "Use Auto Mode when priority has no live channel",
                subtitle = "Switch to Auto Mode when prioritized games have work but no eligible live channel.",
                checked = settings.fallbackToAutoWhenNoPrioritizedChannel,
                onCheckedChange = onFallbackToAutoWhenNoPrioritizedChannelChanged,
            )
            ToggleRow(
                title = "Try unlinked games after linked options",
                subtitle = "Opt in to unlinked watch attempts only after prioritized and linked games are tried.",
                checked = settings.allowWatchingUnlinkedGames,
                onCheckedChange = onAllowWatchingUnlinkedGamesChanged,
            )
        }

        SectionCard {
            SectionTitle("Reliability", "Controls for device reliability and explicit demo data.")
            ToggleRow(
                title = "Demo sample fallback",
                subtitle = "Use local sample campaigns/channels only for explicit demos or endpoint testing.",
                checked = settings.useSampleDataFallback,
                onCheckedChange = onSampleFallbackChanged,
            )
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
            SectionTitle("Debug", "Local diagnostics and session reset.")
            ToggleRow(
                title = "Verbose local logs",
                subtitle = "Record additional local messages for troubleshooting long runs.",
                checked = settings.debugLogging,
                onCheckedChange = onDebugLoggingChanged,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onResetSession, modifier = Modifier.weight(1f)) {
                    Text("Reset Twitch Session")
                }
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
