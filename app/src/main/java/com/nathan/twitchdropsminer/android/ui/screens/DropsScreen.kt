package com.nathan.twitchdropsminer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.text.style.TextAlign
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
import kotlin.math.roundToInt

@Composable
fun DropsScreen(
    settings: AppSettings,
    snapshot: RuntimeSnapshot,
    onToggleGamePriority: (String) -> Unit,
    onMoveGamePriority: (String, Int) -> Unit,
    onSetGamePriority: (String, Int) -> Unit,
    onClearPriority: () -> Unit,
    onSetCampaignExclusion: (Set<String>, Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var compactView by rememberSaveable { mutableStateOf(false) }
    var gamesLinkFilter by rememberSaveable { mutableStateOf(GamesLinkFilter.All) }
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
    val excludedCampaigns = remember(snapshot.campaigns, settings.excludedCampaignIds) {
        snapshot.campaigns.filter { settings.isCampaignExcluded(it) && it.totalDrops > 0 }
    }
    val unloadedExcludedIds = remember(settings.excludedCampaignIds, excludedCampaigns) {
        val loadedExcludedIdKeys = excludedCampaigns
            .map { it.id.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        settings.excludedCampaignIds
            .map { it.trim() }
            .filter { it.isNotBlank() && it.lowercase() !in loadedExcludedIdKeys }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    val allGameSummaries = remember(
        availableCampaigns,
        settings.selectedGamePriority,
        settings.excludedCampaignIds,
    ) {
        availableCampaigns.toGameCampaignSummaries(settings)
    }
    val excludedSummaries = remember(
        excludedCampaigns,
        settings.selectedGamePriority,
        settings.excludedCampaignIds,
    ) {
        excludedCampaigns.toGameCampaignSummaries(settings)
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
                    excludedCampaignIds = emptySet(),
                )
        }
    }
    val filteredAllSummaries = remember(allGameSummaries, query, gamesLinkFilter) {
        allGameSummaries.filter { it.matches(query) && it.matches(gamesLinkFilter) }
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
                    label = { Text("Search games or campaigns") },
                )
            }
            item {
                GamesLinkFilterToggle(
                    currentFilter = gamesLinkFilter,
                    onFilterChanged = { gamesLinkFilter = it },
                )
            }
        }

        when (currentView) {
            CampaignsView.Prioritized -> {
                item {
                    SectionTitle("Priority Order", "Miner tries these games first, in order.")
                }
                if (prioritizedSummaries.isEmpty()) {
                    item {
                        EmptyState(
                            text = "No prioritized games yet.",
                            detail = "Auto Mode will choose eligible campaigns unless you prioritize a game.",
                        )
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
                            onMoveGamePriority = onMoveGamePriority,
                            onSetGamePriority = onSetGamePriority,
                            onSetCampaignExclusion = onSetCampaignExclusion,
                        )
                    }
                }
            }

            CampaignsView.AllAvailable -> {
                item {
                    SectionTitle("Available Campaigns", "Linked, unlinked, claimable, and active game groups.")
                }
                when {
                    availableCampaigns.isEmpty() -> {
                        item {
                            EmptyState(
                                text = "No available Drops campaigns loaded.",
                                detail = "Refresh inventory from the Dashboard after signing in.",
                            )
                        }
                    }

                    filteredAllSummaries.isEmpty() -> {
                        item {
                            EmptyState(gamesLinkFilter.emptyStateMessage(query))
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
                                onMoveGamePriority = onMoveGamePriority,
                                onSetGamePriority = onSetGamePriority,
                                onSetCampaignExclusion = onSetCampaignExclusion,
                            )
                        }
                    }
                }
            }

            CampaignsView.Excluded -> {
                item {
                    SectionTitle(
                        "Excluded Campaigns",
                        "Skipped by priority, Auto Mode, and unlinked probing until restored.",
                    )
                }
                if (settings.excludedCampaignIds.isNotEmpty()) {
                    item {
                        OutlinedButton(
                            onClick = { onSetCampaignExclusion(settings.excludedCampaignIds, false) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Clear All Excluded")
                        }
                    }
                }
                if (excludedSummaries.isEmpty() && unloadedExcludedIds.isEmpty()) {
                    item {
                        EmptyState(
                            text = "No excluded campaigns.",
                            detail = "Excluded campaigns stay visible here so they can be restored later.",
                        )
                    }
                }
                if (excludedSummaries.isNotEmpty()) {
                    items(
                        items = excludedSummaries,
                        key = { "excluded-${it.gameName}" },
                    ) { summary ->
                        GameCampaignCard(
                            summary = summary,
                            selectedCount = settings.selectedGamePriority.size,
                            density = density,
                            mode = GameCardMode.Excluded,
                            onToggleGamePriority = onToggleGamePriority,
                            onMoveGamePriority = onMoveGamePriority,
                            onSetGamePriority = onSetGamePriority,
                            onSetCampaignExclusion = onSetCampaignExclusion,
                        )
                    }
                }
                if (unloadedExcludedIds.isNotEmpty()) {
                    item {
                        SectionTitle(
                            "Excluded IDs Not Currently Loaded",
                            "These persisted exclusions are kept so they can be restored.",
                        )
                    }
                    items(
                        items = unloadedExcludedIds,
                        key = { "excluded-id-$it" },
                    ) { campaignId ->
                        ExcludedCampaignIdRow(
                            campaignId = campaignId,
                            onSetCampaignExclusion = onSetCampaignExclusion,
                        )
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeader(
            title = "Campaigns",
            subtitle = "Priority: ${settings.gamePriorityLabel} - " +
                "Excluded: ${settings.excludedCampaignIds.size}",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onCompactViewChanged(!compactView) },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (compactView) "Regular" else "Compact")
            }
            OutlinedButton(
                onClick = onClearPriority,
                enabled = settings.hasGamePriority,
                modifier = Modifier.weight(1f),
            ) {
                Text("Use Auto Mode")
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
        CampaignsView.entries.forEach { view ->
            CampaignsViewButton(
                view = view,
                currentView = currentView,
                onViewChanged = onViewChanged,
            )
        }
    }
}

@Composable
private fun RowScope.CampaignsViewButton(
    view: CampaignsView,
    currentView: CampaignsView,
    onViewChanged: (CampaignsView) -> Unit,
) {
    if (view == currentView) {
        Button(
            onClick = { onViewChanged(view) },
            modifier = Modifier.weight(1f),
        ) {
            Text(view.label)
        }
    } else {
        OutlinedButton(
            onClick = { onViewChanged(view) },
            modifier = Modifier.weight(1f),
        ) {
            Text(view.label)
        }
    }
}

@Composable
private fun GamesLinkFilterToggle(
    currentFilter: GamesLinkFilter,
    onFilterChanged: (GamesLinkFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GamesLinkFilterButton(
            filter = GamesLinkFilter.All,
            currentFilter = currentFilter,
            onFilterChanged = onFilterChanged,
        )
        GamesLinkFilterButton(
            filter = GamesLinkFilter.Linked,
            currentFilter = currentFilter,
            onFilterChanged = onFilterChanged,
        )
        GamesLinkFilterButton(
            filter = GamesLinkFilter.Unlinked,
            currentFilter = currentFilter,
            onFilterChanged = onFilterChanged,
        )
    }
}

@Composable
private fun RowScope.GamesLinkFilterButton(
    filter: GamesLinkFilter,
    currentFilter: GamesLinkFilter,
    onFilterChanged: (GamesLinkFilter) -> Unit,
) {
    if (filter == currentFilter) {
        Button(
            onClick = { onFilterChanged(filter) },
            modifier = Modifier.weight(1f),
        ) {
            Text(filter.label)
        }
    } else {
        OutlinedButton(
            onClick = { onFilterChanged(filter) },
            modifier = Modifier.weight(1f),
        ) {
            Text(filter.label)
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
    onMoveGamePriority: (String, Int) -> Unit,
    onSetGamePriority: (String, Int) -> Unit,
    onSetCampaignExclusion: (Set<String>, Boolean) -> Unit,
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
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppSurfaceHigh),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 10.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp),
        ) {
            GameCampaignCardHeader(summary = summary, compact = compact)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Progress",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppMuted,
                )
                Text(
                    text = summary.progressLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (summary.claimableDrops > 0) AppAccent else AppMuted,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
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
                    fontWeight = FontWeight.SemiBold,
                )
            }

            when (mode) {
                GameCardMode.AddPriorityButton -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GamesPriorityButton(
                            prioritized = priorityIndex != null,
                            modifier = Modifier.weight(1f),
                            onAddPriority = { onToggleGamePriority(summary.gameName) },
                        )
                        CampaignExclusionButton(
                            summary = summary,
                            restoreOnly = false,
                            modifier = Modifier.weight(1f),
                            onSetCampaignExclusion = onSetCampaignExclusion,
                        )
                    }
                }

                GameCardMode.EditPriorityOnTap -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onMoveGamePriority(summary.gameName, -1) },
                            enabled = priorityIndex != null && priorityIndex > 0,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Move Earlier")
                        }
                        OutlinedButton(
                            onClick = { onMoveGamePriority(summary.gameName, 1) },
                            enabled = priorityIndex != null && priorityIndex < selectedCount - 1,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Move Later")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showPriorityDialog = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Edit Priority")
                        }
                        CampaignExclusionButton(
                            summary = summary,
                            restoreOnly = false,
                            modifier = Modifier.weight(1f),
                            onSetCampaignExclusion = onSetCampaignExclusion,
                        )
                    }
                }

                GameCardMode.Excluded -> {
                    CampaignExclusionButton(
                        summary = summary,
                        restoreOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        onSetCampaignExclusion = onSetCampaignExclusion,
                    )
                }
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
                style = MaterialTheme.typography.titleSmall,
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
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            summary.exclusionStatusLabel?.let {
                StatusPill(it, color = AppWarning.copy(alpha = 0.22f))
            }
            StatusPill(summary.priorityStatusLabel)
            StatusPill(summary.linkStatusLabel, color = summary.linkStatusColor())
        }
    }
}

@Composable
private fun GamesPriorityButton(
    prioritized: Boolean,
    modifier: Modifier = Modifier,
    onAddPriority: () -> Unit,
) {
    if (prioritized) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = modifier.fillMaxWidth(),
        ) {
            Text("Prioritized")
        }
    } else {
        Button(
            onClick = onAddPriority,
            modifier = modifier.fillMaxWidth(),
        ) {
            Text("Prioritize")
        }
    }
}

@Composable
private fun CampaignExclusionButton(
    summary: GameCampaignSummary,
    restoreOnly: Boolean,
    modifier: Modifier = Modifier,
    onSetCampaignExclusion: (Set<String>, Boolean) -> Unit,
) {
    val targetIds = if (restoreOnly || summary.allCampaignsExcluded) {
        summary.excludedCampaignIds
    } else {
        summary.campaignIds
    }
    if (targetIds.isEmpty()) {
        return
    }

    val restoring = restoreOnly || summary.allCampaignsExcluded
    val label = when {
        restoring -> "Restore"
        summary.partiallyExcluded -> "Exclude All"
        else -> "Exclude"
    }
    val onClick = { onSetCampaignExclusion(targetIds, !restoring) }

    if (restoring) {
        Button(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
        ) {
            Text(label)
        }
    }
}

@Composable
private fun ExcludedCampaignIdRow(
    campaignId: String,
    onSetCampaignExclusion: (Set<String>, Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppSurfaceHigh),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Campaign ID",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppMuted,
                )
                Text(
                    text = campaignId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = { onSetCampaignExclusion(setOf(campaignId), false) }) {
                Text("Unexclude")
            }
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
    Excluded,
}

private enum class CampaignsView(val label: String) {
    Prioritized("Priority"),
    AllAvailable("Available"),
    Excluded("Excluded"),
}

private enum class GamesLinkFilter(val label: String) {
    All("All"),
    Linked("Linked"),
    Unlinked("Unlinked"),
}

private enum class GameAccountLinkState(val label: String) {
    Linked("Linked"),
    Unlinked("Unlinked"),
    Unknown("Unknown"),
}

private data class GameCampaignSummary(
    val gameName: String,
    val campaigns: List<Campaign>,
    val priorityIndex: Int?,
    val excludedCampaignIds: Set<String>,
) {
    private val drops: List<CampaignDrop> = campaigns.flatMap { it.drops }
    val campaignIds: Set<String> = campaigns.map { it.id }.toSet()
    val campaignCount: Int = campaigns.size
    val excludedCampaignCount: Int = campaigns.count { it.id in excludedCampaignIds }
    val allCampaignsExcluded: Boolean = campaignCount > 0 && excludedCampaignCount == campaignCount
    val partiallyExcluded: Boolean = excludedCampaignCount > 0 && !allCampaignsExcluded
    val activeCampaigns: Int = campaigns.count { it.active }
    val linkedCampaigns: Int = campaigns.count { it.linked }
    val earnableCampaigns: Int = campaigns.count { it.canEarnLocally }
    val claimedDrops: Int = campaigns.sumOf { it.claimedDrops }
    val totalDrops: Int = campaigns.sumOf { it.totalDrops }
    val claimableDrops: Int = drops.count { it.canClaim }
    val remainingMinutes: Int = drops.sumOf { it.remainingMinutes }
    val linkState: GameAccountLinkState = when {
        campaigns.isEmpty() -> GameAccountLinkState.Unknown
        linkedCampaigns > 0 -> GameAccountLinkState.Linked
        campaigns.any { (it.linkStatusKnown && !it.linked) || it.linkUrl != null } ->
            GameAccountLinkState.Unlinked
        else -> GameAccountLinkState.Unknown
    }
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
            val current = drops.sumOf { drop -> drop.watchedMinutes }
            current.toFloat() / required.toFloat()
        }
    }
    val priorityStatusLabel: String =
        priorityIndex?.let { "Priority ${it + 1}" } ?: "Auto candidate"
    val exclusionStatusLabel: String? = when {
        allCampaignsExcluded -> "Excluded"
        partiallyExcluded -> "Partly Excluded"
        else -> null
    }
    val linkStatusLabel: String = linkState.label
    val progressPercent: Int = (progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val claimStatusLabel: String = when {
        totalDrops <= 0 -> "No drops"
        claimableDrops > 0 -> "$claimableDrops ready to claim"
        claimedDrops >= totalDrops -> "All claimed"
        else -> "$claimedDrops/$totalDrops claimed"
    }
    val progressLine: String = "$progressPercent% - $claimStatusLabel"
    val regularStatusLine: String = when {
        campaigns.isEmpty() -> "No available campaign details loaded."
        allCampaignsExcluded -> "Excluded from priority, Auto Mode, and unlinked probing."
        partiallyExcluded -> "$excludedCampaignCount/$campaignCount campaigns excluded from mining."
        else -> "$activeCampaigns/$campaignCount active - $linkedCampaigns linked - " +
            "${remainingMinutes}m remaining"
    }
    val compactStatusLine: String = when {
        campaigns.isEmpty() -> "No loaded campaign details"
        allCampaignsExcluded -> "Excluded - $claimStatusLabel"
        partiallyExcluded -> "$excludedCampaignCount excluded - $claimStatusLabel"
        claimableDrops > 0 -> "${linkState.label}, $claimableDrops ready, drops $claimedDrops/$totalDrops"
        else -> "${linkState.label}, $activeCampaigns/$campaignCount active, drops $claimedDrops/$totalDrops, " +
            "${remainingMinutes}m left"
    }
    val eligibilityLabel: String = when {
        campaigns.isEmpty() -> "No available campaign details loaded"
        allCampaignsExcluded -> "Excluded from mining until restored"
        partiallyExcluded -> "$excludedCampaignCount ${if (excludedCampaignCount == 1) "campaign" else "campaigns"} excluded from mining"
        claimableDrops > 0 -> "$claimableDrops drops ready to claim"
        earnableCampaigns > 0 -> "Eligible linked campaign available"
        linkState == GameAccountLinkState.Unlinked -> "Link account before this game can earn drops"
        linkState == GameAccountLinkState.Unknown -> "Linked account status is unknown"
        activeCampaigns == 0 -> "Campaigns are not active yet"
        totalDrops > 0 && claimedDrops >= totalDrops -> "All drops claimed"
        else -> "No locally earnable drops right now"
    }
}

private fun GameCampaignSummary.eligibilityColor() = when {
    allCampaignsExcluded || partiallyExcluded -> AppWarning
    claimableDrops > 0 || earnableCampaigns > 0 -> AppAccent
    linkState == GameAccountLinkState.Unlinked -> AppWarning
    else -> AppMuted
}

private fun GameCampaignSummary.linkStatusColor() = when (linkState) {
    GameAccountLinkState.Linked -> AppAccent.copy(alpha = 0.16f)
    GameAccountLinkState.Unlinked -> AppWarning.copy(alpha = 0.22f)
    GameAccountLinkState.Unknown -> AppMuted.copy(alpha = 0.18f)
}

private fun GameCampaignSummary.matches(query: String): Boolean {
    val trimmed = query.trim()
    return trimmed.isBlank() ||
        gameName.contains(trimmed, ignoreCase = true) ||
        campaigns.any { it.name.contains(trimmed, ignoreCase = true) }
}

private fun GameCampaignSummary.matches(filter: GamesLinkFilter): Boolean = when (filter) {
    GamesLinkFilter.All -> true
    GamesLinkFilter.Linked -> linkState == GameAccountLinkState.Linked
    GamesLinkFilter.Unlinked -> linkState == GameAccountLinkState.Unlinked
}

private fun GamesLinkFilter.emptyStateMessage(query: String): String =
    if (query.isBlank()) {
        when (this) {
            GamesLinkFilter.All -> "No games match the current filter."
            GamesLinkFilter.Linked -> "No linked games loaded."
            GamesLinkFilter.Unlinked -> "No unlinked games loaded."
        }
    } else {
        "No games match the current filter."
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
            excludedCampaignIds = campaigns
                .filter { settings.isCampaignExcluded(it) }
                .map { it.id }
                .toSet(),
        )
    }.sortedWith(
        compareByDescending<GameCampaignSummary> { it.claimableDrops > 0 }
            .thenByDescending { it.earnableCampaigns > 0 }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.gameName },
    )
}
