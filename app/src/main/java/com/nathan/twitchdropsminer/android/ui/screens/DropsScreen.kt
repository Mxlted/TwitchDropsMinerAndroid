package com.nathan.twitchdropsminer.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import com.nathan.twitchdropsminer.android.ui.theme.AppAccent
import com.nathan.twitchdropsminer.android.ui.theme.AppMuted
import com.nathan.twitchdropsminer.android.ui.theme.AppSurfaceHigh
import com.nathan.twitchdropsminer.android.ui.theme.AppWarning

@Composable
fun DropsScreen(
    settings: AppSettings,
    snapshot: RuntimeSnapshot,
    onToggleGamePriority: (String) -> Unit,
    onSetGamePriority: (String, Int) -> Unit,
    onClearPriority: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var compactView by rememberSaveable { mutableStateOf(false) }
    var currentView by rememberSaveable {
        mutableStateOf(
            if (settings.hasGamePriority) {
                CampaignsView.Prioritized
            } else {
                CampaignsView.AllAvailable
            },
        )
    }
    val density = if (compactView) CampaignCardDensity.Compact else CampaignCardDensity.Regular
    val availableCampaigns = remember(snapshot.campaigns) {
        snapshot.campaigns.filter { !it.expired && it.totalDrops > 0 }
    }
    val allGameSummaries = remember(availableCampaigns, settings.selectedGamePriority) {
        availableCampaigns.toGameCampaignSummaries(settings)
    }
    val allSummaryByName = remember(allGameSummaries) {
        allGameSummaries.associateBy { it.gameName.lowercase() }
    }
    val prioritizedSummaries = remember(settings.selectedGamePriority, allSummaryByName) {
        settings.selectedGamePriority.mapIndexed { index, gameName ->
            allSummaryByName[gameName.lowercase()]
                ?.copy(priorityIndex = index)
                ?: GameCampaignSummary(
                    gameName = gameName,
                    campaigns = emptyList(),
                    priorityIndex = index,
                )
        }
    }
    val filteredAllSummaries = remember(allGameSummaries, query) {
        allGameSummaries.filter { it.matches(query) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactView) 8.dp else 10.dp),
    ) {
        item {
            CampaignsHeader(
                settings = settings,
                compactView = compactView,
                onCompactViewChanged = { compactView = it },
                onClearPriority = onClearPriority,
            )
        }
        item {
            CampaignsViewToggle(
                currentView = currentView,
                onViewChanged = { currentView = it },
            )
        }
        if (currentView == CampaignsView.AllAvailable) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Filter available games") },
                )
            }
        }

        when (currentView) {
            CampaignsView.Prioritized -> {
                item {
                    SectionTitle("Prioritized", "Miner tries these games first, in order.")
                }
                if (prioritizedSummaries.isEmpty()) {
                    item {
                        EmptyState("No prioritized games yet. Auto Mode will choose eligible campaigns.")
                    }
                } else {
                    items(
                        items = prioritizedSummaries,
                        key = { "priority-${it.gameName}" },
                    ) { summary ->
                        GameCampaignCard(
                            summary = summary,
                            selectedCount = settings.selectedGamePriority.size,
                            density = density,
                            mode = GameCardMode.EditPriorityOnTap,
                            onToggleGamePriority = onToggleGamePriority,
                            onSetGamePriority = onSetGamePriority,
                        )
                    }
                }
            }

            CampaignsView.AllAvailable -> {
                item {
                    SectionTitle("Games", "Available Drops campaigns.")
                }
                when {
                    availableCampaigns.isEmpty() -> {
                        item {
                            EmptyState("No games with available Drops campaigns loaded.")
                        }
                    }

                    filteredAllSummaries.isEmpty() -> {
                        item {
                            EmptyState("No games match the current filter.")
                        }
                    }

                    else -> {
                        items(
                            items = filteredAllSummaries,
                            key = { "all-${it.gameName}" },
                        ) { summary ->
                            GameCampaignCard(
                                summary = summary,
                                selectedCount = settings.selectedGamePriority.size,
                                density = density,
                                mode = GameCardMode.AddPriorityButton,
                                onToggleGamePriority = onToggleGamePriority,
                                onSetGamePriority = onSetGamePriority,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CampaignsHeader(
    settings: AppSettings,
    compactView: Boolean,
    onCompactViewChanged: (Boolean) -> Unit,
    onClearPriority: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Campaigns",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Priority: ${settings.gamePriorityLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = AppMuted,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onCompactViewChanged(!compactView) },
            ) {
                Text(if (compactView) "Regular" else "Compact")
            }
            OutlinedButton(
                onClick = onClearPriority,
                enabled = settings.hasGamePriority,
            ) {
                Text("Auto")
            }
        }
    }
}

@Composable
private fun CampaignsViewToggle(
    currentView: CampaignsView,
    onViewChanged: (CampaignsView) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (currentView == CampaignsView.Prioritized) {
            Button(
                onClick = { onViewChanged(CampaignsView.Prioritized) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Prioritized")
            }
        } else {
            OutlinedButton(
                onClick = { onViewChanged(CampaignsView.Prioritized) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Prioritized")
            }
        }

        if (currentView == CampaignsView.AllAvailable) {
            Button(
                onClick = { onViewChanged(CampaignsView.AllAvailable) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Games")
            }
        } else {
            OutlinedButton(
                onClick = { onViewChanged(CampaignsView.AllAvailable) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Games")
            }
        }
    }
}

@Composable
private fun GameCampaignCard(
    summary: GameCampaignSummary,
    selectedCount: Int,
    density: CampaignCardDensity,
    mode: GameCardMode,
    onToggleGamePriority: (String) -> Unit,
    onSetGamePriority: (String, Int) -> Unit,
) {
    val priorityIndex = summary.priorityIndex
    val compact = density == CampaignCardDensity.Compact
    val editsPriorityOnTap = mode == GameCardMode.EditPriorityOnTap
    var showPriorityDialog by rememberSaveable(summary.gameName) { mutableStateOf(false) }

    if (showPriorityDialog && editsPriorityOnTap) {
        PriorityDialog(
            summary = summary,
            selectedCount = selectedCount,
            onDismiss = { showPriorityDialog = false },
            onSetPriority = { priority ->
                onSetGamePriority(summary.gameName, priority)
                showPriorityDialog = false
            },
            onRemovePriority = {
                onToggleGamePriority(summary.gameName)
                showPriorityDialog = false
            },
        )
    }

    Card(
        modifier = if (editsPriorityOnTap) {
            Modifier
                .fillMaxWidth()
                .clickable { showPriorityDialog = true }
        } else {
            Modifier.fillMaxWidth()
        },
        colors = CardDefaults.cardColors(containerColor = AppSurfaceHigh),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 10.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp),
        ) {
            GameCampaignCardHeader(summary = summary, compact = compact)

            LinearProgressIndicator(
                progress = { summary.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = AppAccent,
            )

            if (compact) {
                Text(
                    text = summary.compactStatusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = summary.regularStatusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppMuted,
                )
                Text(
                    text = summary.eligibilityLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = summary.eligibilityColor(),
                )
            }

            if (mode == GameCardMode.AddPriorityButton) {
                GamesPriorityButton(
                    prioritized = priorityIndex != null,
                    onAddPriority = { onToggleGamePriority(summary.gameName) },
                )
            }
        }
    }
}

@Composable
private fun GameCampaignCardHeader(
    summary: GameCampaignSummary,
    compact: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = summary.gameName,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    text = summary.campaignNames,
                    color = AppMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StatusPill(summary.priorityStatusLabel)
    }
}

@Composable
private fun GamesPriorityButton(
    prioritized: Boolean,
    onAddPriority: () -> Unit,
) {
    if (prioritized) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Already Prioritized")
        }
    } else {
        Button(
            onClick = onAddPriority,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add to Prioritized")
        }
    }
}

@Composable
private fun PriorityDialog(
    summary: GameCampaignSummary,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onSetPriority: (Int) -> Unit,
    onRemovePriority: () -> Unit,
) {
    val priorityIndex = summary.priorityIndex
    val defaultPriority = priorityIndex?.plus(1) ?: selectedCount + 1
    var priorityText by remember(summary.gameName, priorityIndex, selectedCount) {
        mutableStateOf(defaultPriority.toString())
    }
    val parsedPriority = priorityText.toIntOrNull()
    val canSave = parsedPriority?.let { it > 0 } == true

    fun commitPriority() {
        parsedPriority?.takeIf { it > 0 }?.let(onSetPriority)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(summary.gameName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = priorityIndex?.let { "Current priority ${it + 1}" }
                        ?: "Set a priority to add this game.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppMuted,
                )
                OutlinedTextField(
                    value = priorityText,
                    onValueChange = { value ->
                        priorityText = value.filter(Char::isDigit).take(3)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Priority") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { commitPriority() },
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { commitPriority() },
                enabled = canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (priorityIndex != null) {
                    TextButton(onClick = onRemovePriority) {
                        Text("Remove")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

private enum class CampaignCardDensity {
    Regular,
    Compact,
}

private enum class GameCardMode {
    EditPriorityOnTap,
    AddPriorityButton,
}

private enum class CampaignsView {
    Prioritized,
    AllAvailable,
}

private data class GameCampaignSummary(
    val gameName: String,
    val campaigns: List<Campaign>,
    val priorityIndex: Int?,
) {
    private val drops: List<CampaignDrop> = campaigns.flatMap { it.drops }
    val campaignCount: Int = campaigns.size
    val activeCampaigns: Int = campaigns.count { it.active }
    val linkedCampaigns: Int = campaigns.count { it.linked }
    val earnableCampaigns: Int = campaigns.count { it.canEarnLocally }
    val claimedDrops: Int = campaigns.sumOf { it.claimedDrops }
    val totalDrops: Int = campaigns.sumOf { it.totalDrops }
    val claimableDrops: Int = drops.count { it.canClaim }
    val remainingMinutes: Int = drops.sumOf { it.remainingMinutes }
    val hasLinkTarget: Boolean = campaigns.any { it.linkUrl != null }
    val campaignNames: String = when {
        campaigns.isEmpty() -> "No loaded campaign details"
        else -> campaigns
            .take(2)
            .joinToString { it.name.ifBlank { "Unnamed campaign" } } +
            if (campaigns.size > 2) " +${campaigns.size - 2} more" else ""
    }
    val progress: Float = run {
        val required = drops.sumOf { it.requiredMinutes.coerceAtLeast(0) }
        if (required <= 0) {
            0f
        } else {
            val current = drops.sumOf { drop ->
                drop.currentMinutes.coerceIn(0, drop.requiredMinutes.coerceAtLeast(0))
            }
            current.toFloat() / required.toFloat()
        }
    }
    val priorityStatusLabel: String =
        priorityIndex?.let { "Priority ${it + 1}" } ?: "Available"
    val regularStatusLine: String = when {
        campaigns.isEmpty() -> "No available campaign details loaded."
        else -> "$activeCampaigns/$campaignCount active campaigns, " +
            "$claimedDrops/$totalDrops drops claimed, ${remainingMinutes}m remaining"
    }
    val compactStatusLine: String = when {
        campaigns.isEmpty() -> "No loaded campaign details"
        claimableDrops > 0 -> "$claimableDrops ready, drops $claimedDrops/$totalDrops"
        else -> "$activeCampaigns/$campaignCount active, drops $claimedDrops/$totalDrops, " +
            "${remainingMinutes}m left"
    }
    val eligibilityLabel: String = when {
        campaigns.isEmpty() -> "No available campaign details loaded"
        claimableDrops > 0 -> "$claimableDrops drops ready to claim"
        earnableCampaigns > 0 -> "Eligible linked campaign available"
        linkedCampaigns == 0 && hasLinkTarget -> "Link account before this game can earn drops"
        activeCampaigns == 0 -> "Campaigns are not active yet"
        totalDrops > 0 && claimedDrops >= totalDrops -> "All drops claimed"
        else -> "No locally earnable drops right now"
    }
}

private fun GameCampaignSummary.eligibilityColor() = when {
    claimableDrops > 0 || earnableCampaigns > 0 -> AppAccent
    linkedCampaigns == 0 && hasLinkTarget -> AppWarning
    else -> AppMuted
}

private fun GameCampaignSummary.matches(query: String): Boolean {
    val trimmed = query.trim()
    return trimmed.isBlank() ||
        gameName.contains(trimmed, ignoreCase = true) ||
        campaigns.any { it.name.contains(trimmed, ignoreCase = true) }
}

private fun List<Campaign>.toGameCampaignSummaries(settings: AppSettings): List<GameCampaignSummary> {
    val groups = groupBy { it.gameName.ifBlank { "Unknown game" } }
    return groups.map { (gameName, campaigns) ->
        GameCampaignSummary(
            gameName = gameName,
            campaigns = campaigns.sortedWith(
                compareByDescending<Campaign> { it.active }
                    .thenByDescending { it.linked }
                    .thenBy { it.endsAt },
            ),
            priorityIndex = settings.gamePriorityIndex(gameName),
        )
    }.sortedWith(
        compareByDescending<GameCampaignSummary> { it.claimableDrops > 0 }
            .thenByDescending { it.earnableCampaigns > 0 }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.gameName },
    )
}
