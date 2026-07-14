package com.nathan.twitchdropsminer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import com.nathan.twitchdropsminer.android.ui.theme.AppAccent
import com.nathan.twitchdropsminer.android.ui.theme.AppMuted
import kotlin.math.roundToInt

@Composable
fun ActivityScreen(snapshot: RuntimeSnapshot) {
    val visibleActivity = snapshot.activity.takeLast(120)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = "Activity",
            subtitle = "Current runtime work and recent timeline events",
        )

        SectionCard {
            SectionTitle("Current Work", snapshot.currentTask)
            DetailRow("Phase", snapshot.phase.uiLabel())
            DetailRow("Channel", snapshot.watchingChannel?.name ?: "None")
            DetailRow("Campaign", snapshot.activeCampaign?.gameName ?: "None")
            if (snapshot.activeDrop == null) {
                Text(
                    text = "No active drop is selected right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppMuted,
                )
            } else {
                snapshot.activeDrop.let { drop ->
                    Text(
                        text = drop.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LinearProgressIndicator(
                        progress = { drop.progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = AppAccent,
                    )
                    Text(
                        "${(drop.progressFraction * 100f).roundToInt()}% • " +
                            "${drop.watchedMinutes}/${drop.requiredMinutes}m watched",
                        color = AppMuted,
                    )
                    if (snapshot.phase == RuntimePhase.Claiming) {
                        Text(snapshot.currentTask, color = AppAccent)
                    }
                }
            }
        }

        SectionTitle("Runtime Timeline", "${visibleActivity.size} shown of ${snapshot.activity.size} events")
        if (visibleActivity.isEmpty()) {
            EmptyState(
                text = "No local runtime activity yet.",
                detail = "Start login, refresh inventory, or begin mining to populate the timeline.",
            )
        } else {
            LatestFollowingLazyColumn(
                itemCount = visibleActivity.size,
                modifier = Modifier.weight(1f),
                jumpLabel = "Jump to latest",
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    items = visibleActivity,
                    key = { entry -> "${entry.timestamp}-${entry.state}-${entry.title}" },
                ) { entry ->
                    RuntimeActivityLine(entry)
                }
            }
        }
    }
}
