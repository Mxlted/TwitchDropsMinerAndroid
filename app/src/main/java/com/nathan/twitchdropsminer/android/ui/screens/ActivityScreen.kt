package com.nathan.twitchdropsminer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.ui.theme.AppAccent
import com.nathan.twitchdropsminer.android.ui.theme.AppMuted

@Composable
fun ActivityScreen(snapshot: RuntimeSnapshot) {
    val visibleActivity = snapshot.activity.takeLast(120)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Activity",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        SectionCard {
            SectionTitle("Current Work", snapshot.currentTask)
            TwoColumnMetrics(
                first = "Channel" to (snapshot.watchingChannel?.name ?: "None"),
                second = "Campaign" to (snapshot.activeCampaign?.gameName ?: "None"),
            )
            snapshot.activeDrop?.let { drop ->
                Text("${drop.name}: ${drop.currentMinutes}/${drop.requiredMinutes}m", color = AppMuted)
                if (snapshot.phase == RuntimePhase.Claiming) {
                    Text(snapshot.currentTask, color = AppAccent)
                }
            }
        }

        SectionTitle("Runtime Timeline", "${snapshot.activity.size} events")
        if (visibleActivity.isEmpty()) {
            EmptyState("No local runtime activity yet.")
        } else {
            LatestFollowingLazyColumn(
                itemCount = visibleActivity.size,
                modifier = Modifier.weight(1f),
                jumpLabel = "Jump to latest",
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleActivity) { entry ->
                    Text(
                        text = entry.toLine(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = AppMuted,
                    )
                }
            }
        }
    }
}
