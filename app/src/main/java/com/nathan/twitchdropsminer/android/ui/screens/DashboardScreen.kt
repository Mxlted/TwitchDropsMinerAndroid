package com.nathan.twitchdropsminer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import com.nathan.twitchdropsminer.android.ui.theme.AppAccent
import com.nathan.twitchdropsminer.android.ui.theme.AppError
import com.nathan.twitchdropsminer.android.ui.theme.AppMuted
import com.nathan.twitchdropsminer.android.ui.theme.AppText

@Composable
fun DashboardScreen(
    settings: AppSettings,
    snapshot: RuntimeSnapshot,
    isRefreshing: Boolean,
    onStartLogin: () -> Unit,
    onOpenActivation: (String) -> Unit,
    onRefresh: () -> Unit,
    onStartMining: () -> Unit,
    onStopMining: () -> Unit,
    onFindNewChannel: () -> Unit,
    onSelectChannel: (Long) -> Unit,
    onEnterDimScreen: () -> Unit,
) {
    var showChannelPicker by rememberSaveable { mutableStateOf(false) }
    val activelyWorking = snapshot.phase !in setOf(
        RuntimePhase.Stopped,
        RuntimePhase.Idle,
        RuntimePhase.Error,
    )
    val currentChannel = snapshot.watchingChannel
    val compatibleChannels = (listOfNotNull(currentChannel) + snapshot.channels)
        .distinctBy { channel -> channel.id }
        .filter { channel ->
            channel.id == currentChannel?.id ||
                (channel.online && channel.dropsEnabled && !channel.broadcastId.isNullOrBlank())
        }
        .sortedWith(
            compareByDescending<Channel> { channel -> channel.id == currentChannel?.id }
                .thenByDescending { channel -> channel.aclBased }
                .thenByDescending { channel -> channel.viewers ?: -1 },
        )

    LaunchedEffect(snapshot.isRunning) {
        if (!snapshot.isRunning) {
            showChannelPicker = false
        }
    }

    if (showChannelPicker) {
        ChannelPickerDialog(
            campaignName = snapshot.activeCampaign?.gameName ?: "Current campaign",
            channels = compatibleChannels,
            currentChannelId = currentChannel?.id,
            loading = snapshot.channelSearchInProgress,
            onSelectChannel = { channelId ->
                onSelectChannel(channelId)
                showChannelPicker = false
            },
            onDismiss = { showChannelPicker = false },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(
            title = "Dashboard",
            subtitle = "Local Android miner runtime",
            trailing = { StatusPill(snapshot.phase.uiLabel(), phase = snapshot.phase) },
        )

        if (isRefreshing || activelyWorking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AppAccent)
        }

        SectionCard {
            SectionTitle("Runtime Status", snapshot.currentTask)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatusPill(snapshot.phase.uiLabel(), phase = snapshot.phase)
                StatusPill(
                    label = if (snapshot.isRunning) "Running" else "Not running",
                    color = if (snapshot.isRunning) {
                        AppAccent.copy(alpha = 0.18f)
                    } else {
                        AppMuted.copy(alpha = 0.16f)
                    },
                )
            }
            DetailRow("Current phase", snapshot.phase.uiLabel())
            DetailRow("Selected campaign", snapshot.activeCampaign.selectedCampaignLabel())
            DetailRow("Selected channel", snapshot.watchingChannel?.name ?: "None")
            DetailRow("Next action", snapshot.nextActionLabel())
            DetailRow(
                label = snapshot.reasonLabel(),
                value = snapshot.error ?: snapshot.progressSummary,
                valueColor = if (snapshot.error != null) AppError else AppText,
            )
            TwoColumnMetrics(
                first = "Last update" to snapshot.lastUpdate.timeLabel(),
                second = "Claimed" to snapshot.dropsClaimedThisSession.toString(),
            )
        }

        SectionCard {
            SectionTitle("Twitch Session", snapshot.account.statusText)
            TwoColumnMetrics(
                first = "User ID" to (snapshot.account.userId ?: "None"),
                second = "Auth" to snapshot.account.state.statusLabel(),
            )
            if (snapshot.account.oauthCode != null) {
                Text("Device code: ${snapshot.account.oauthCode}", fontWeight = FontWeight.Bold)
                Text(snapshot.account.oauthUrl ?: "https://www.twitch.tv/activate", color = AppMuted)
                Button(
                    onClick = { snapshot.account.oauthUrl?.let(onOpenActivation) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Twitch Activation")
                }
            }
            if (snapshot.account.state != LoginState.LoggedIn) {
                Button(onClick = onStartLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("Start Twitch Login")
                }
            }
        }

        SectionCard {
            SectionTitle("Inventory And Drops", snapshot.progressSummary)
            TwoColumnMetrics(
                first = "Campaigns" to snapshot.campaigns.size.toString(),
                second = "Game priority" to settings.gamePriorityLabel,
            )
            TwoColumnMetrics(
                first = "Active" to snapshot.activeCampaignCount.toString(),
                second = "Watching" to (snapshot.watchingChannel?.name ?: "None"),
            )
            if (snapshot.activeDrop == null) {
                EmptyState(
                    text = "No active drop selected.",
                    detail = "Start mining or refresh inventory to load eligible work.",
                )
            } else {
                snapshot.activeDrop.let { drop ->
                    Text(
                        text = drop.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LinearProgressIndicator(
                        progress = { drop.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = AppAccent,
                    )
                    Text(
                        text = "${drop.currentMinutes}/${drop.requiredMinutes}m watched, " +
                            "${drop.remainingMinutes}m remaining",
                        color = AppMuted,
                    )
                    if (snapshot.phase == RuntimePhase.Claiming) {
                        Text(text = snapshot.currentTask, color = AppAccent)
                    }
                }
            }
        }

        SectionCard {
            SectionTitle(
                title = "Controls",
                subtitle = if (settings.runInForeground) {
                    "Mining starts through the foreground service notification."
                } else {
                    "Mining runs only while the app process stays active."
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onStartMining,
                    modifier = Modifier.weight(1f),
                    enabled = !snapshot.isRunning && snapshot.account.state == LoginState.LoggedIn,
                ) {
                    Text("Start")
                }
                OutlinedButton(
                    onClick = onStopMining,
                    modifier = Modifier.weight(1f),
                    enabled = snapshot.isRunning,
                ) {
                    Text("Stop")
                }
            }
            OutlinedButton(
                onClick = {
                    showChannelPicker = true
                    onFindNewChannel()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = snapshot.isRunning &&
                    snapshot.phase == RuntimePhase.Watching &&
                    snapshot.activeCampaign != null &&
                    snapshot.watchingChannel != null &&
                    !snapshot.channelSearchInProgress,
            ) {
                Text(if (snapshot.channelSearchInProgress) "Finding Channels" else "Find New Channel")
            }
            Text(
                text = "Opens a refreshed list of compatible Drops-enabled streamers. The current channel stays active until you choose another.",
                style = MaterialTheme.typography.bodySmall,
                color = AppMuted,
            )
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRefreshing && snapshot.account.state == LoginState.LoggedIn,
            ) {
                Text(if (isRefreshing) "Refreshing Inventory" else "Refresh Inventory")
            }
            if (snapshot.account.state != LoginState.LoggedIn) {
                Text(
                    text = "Sign in to Twitch before refreshing inventory or starting the miner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppMuted,
                )
            }
            OutlinedButton(
                onClick = onEnterDimScreen,
                modifier = Modifier.fillMaxWidth(),
                enabled = settings.keepActiveScreenMode,
            ) {
                Text("Enter Keep Active Screen")
            }
            if (!settings.keepActiveScreenMode) {
                Text(
                    text = "Enable Keep Active Screen in Settings to use the dim tap-to-return view.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppMuted,
                )
            }
        }
    }
}

@Composable
private fun ChannelPickerDialog(
    campaignName: String,
    channels: List<Channel>,
    currentChannelId: Long?,
    loading: Boolean,
    onSelectChannel: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val alternateCount = channels.count { channel -> channel.id != currentChannelId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a Channel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = campaignName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Checking compatible live streamers…")
                    }
                } else {
                    Text(
                        text = if (alternateCount == 0) {
                            "No alternate compatible streamer was found. The current channel will remain active."
                        } else {
                            "$alternateCount alternate compatible ${if (alternateCount == 1) "streamer" else "streamers"} found."
                        },
                        color = AppMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = channels,
                            key = { channel -> channel.id },
                        ) { channel ->
                            val isCurrent = channel.id == currentChannelId
                            OutlinedButton(
                                onClick = { onSelectChannel(channel.id) },
                                enabled = !isCurrent,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = if (isCurrent) "${channel.name} (Current)" else channel.name,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = buildString {
                                            append(channel.viewers?.let { "$it viewers" } ?: "Viewer count unavailable")
                                            if (channel.aclBased) append(" • Campaign allowed")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

private fun Campaign?.selectedCampaignLabel(): String =
    this?.let { campaign ->
        if (campaign.name.isBlank() || campaign.name == campaign.gameName) {
            campaign.gameName
        } else {
            "${campaign.gameName} - ${campaign.name}"
        }
    } ?: "None"

private fun RuntimeSnapshot.nextActionLabel(): String = when {
    error != null -> "Resolve the error, then retry."
    account.isActionRequired -> "Approve the Twitch device login."
    phase == RuntimePhase.Stopped -> "Start mining or refresh inventory."
    phase == RuntimePhase.Idle -> currentTask
    isRunning -> currentTask
    else -> progressSummary
}

private fun RuntimeSnapshot.reasonLabel(): String = when {
    error != null -> "Error reason"
    phase == RuntimePhase.Idle || phase == RuntimePhase.Stopped -> "Idle reason"
    else -> "Status message"
}

private fun LoginState.statusLabel(): String = when (this) {
    LoginState.Unknown -> "Unknown"
    LoginState.LoggedOut -> "Logged out"
    LoginState.LoginRequired -> "Login required"
    LoginState.LoggedIn -> "Logged in"
    LoginState.Expired -> "Expired"
}
