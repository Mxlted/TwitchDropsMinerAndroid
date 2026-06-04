package com.nathan.twitchdropsminer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import com.nathan.twitchdropsminer.android.ui.theme.AppAccent
import com.nathan.twitchdropsminer.android.ui.theme.AppError
import com.nathan.twitchdropsminer.android.ui.theme.AppMuted

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
    onEnterDimScreen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Dashboard",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Local Android miner runtime",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppMuted,
                )
            }
            StatusPill(snapshot.phase.name, phase = snapshot.phase)
        }

        if (isRefreshing || snapshot.isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AppAccent)
        }

        SectionCard {
            SectionTitle("Runtime", snapshot.currentTask)
            TwoColumnMetrics(
                first = "Last update" to snapshot.lastUpdate.timeLabel(),
                second = "Claimed" to snapshot.dropsClaimedThisSession.toString(),
            )
            Text(text = snapshot.progressSummary, color = AppMuted)
            if (snapshot.error != null) {
                Text(text = snapshot.error, color = AppError)
            }
        }

        SectionCard {
            SectionTitle("Twitch Session", snapshot.account.statusText)
            TwoColumnMetrics(
                first = "User ID" to (snapshot.account.userId ?: "None"),
                second = "Auth" to snapshot.account.state.name,
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
                OutlinedButton(onClick = onStartLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("Start Twitch Login")
                }
            }
        }

        SectionCard {
            SectionTitle("Progress")
            TwoColumnMetrics(
                first = "Campaigns" to snapshot.campaigns.size.toString(),
                second = "Game priority" to settings.gamePriorityLabel,
            )
            TwoColumnMetrics(
                first = "Active" to snapshot.activeCampaignCount.toString(),
                second = "Watching" to (snapshot.watchingChannel?.name ?: "None"),
            )
            snapshot.activeDrop?.let { drop ->
                Text(
                    text = "${drop.name}: ${drop.currentMinutes}/${drop.requiredMinutes}m",
                    color = AppMuted,
                )
                if (snapshot.phase == RuntimePhase.Claiming) {
                    Text(text = snapshot.currentTask, color = AppAccent)
                }
            }
        }

        SectionCard {
            SectionTitle("Controls")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onStartMining, modifier = Modifier.weight(1f), enabled = !snapshot.isRunning) {
                    Text("Start")
                }
                OutlinedButton(onClick = onStopMining, modifier = Modifier.weight(1f)) {
                    Text("Stop")
                }
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh Inventory")
            }
            OutlinedButton(
                onClick = onEnterDimScreen,
                modifier = Modifier.fillMaxWidth(),
                enabled = settings.keepActiveScreenMode,
            ) {
                Text("Enter Keep Active Screen")
            }
        }
    }
}
