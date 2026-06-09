package com.nathan.twitchdropsminer.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nathan.twitchdropsminer.android.data.model.LocalLogEntry
import com.nathan.twitchdropsminer.android.data.model.RuntimeActivity
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.ui.theme.AppAccent
import com.nathan.twitchdropsminer.android.ui.theme.AppAccentAlt
import com.nathan.twitchdropsminer.android.ui.theme.AppError
import com.nathan.twitchdropsminer.android.ui.theme.AppMuted
import com.nathan.twitchdropsminer.android.ui.theme.AppText
import com.nathan.twitchdropsminer.android.ui.theme.AppSurfaceHigh
import com.nathan.twitchdropsminer.android.ui.theme.AppWarning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private const val LatestFollowTrailingItemThreshold = 1

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = if (trailing == null) 0.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppMuted,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppSurfaceHigh),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.16f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppMuted,
            )
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    modifier: Modifier = Modifier,
    phase: RuntimePhase? = null,
    color: Color? = null,
) {
    val background = color ?: when (phase) {
        RuntimePhase.Watching -> AppAccent.copy(alpha = 0.22f)
        RuntimePhase.Fetching,
        RuntimePhase.LoadingInventory,
        RuntimePhase.SelectingCampaign,
        RuntimePhase.FindingChannel,
        RuntimePhase.Authenticating,
        RuntimePhase.Claiming -> AppAccentAlt.copy(alpha = 0.22f)
        RuntimePhase.Error -> AppError.copy(alpha = 0.22f)
        RuntimePhase.Idle -> AppWarning.copy(alpha = 0.22f)
        RuntimePhase.Connecting -> AppAccentAlt.copy(alpha = 0.14f)
        RuntimePhase.Stopped -> AppMuted.copy(alpha = 0.18f)
        null -> AppAccent.copy(alpha = 0.16f)
    }
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = AppMuted)
    }
}

@Composable
fun TwoColumnMetrics(first: Pair<String, String>, second: Pair<String, String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Metric(label = first.first, value = first.second, modifier = Modifier.weight(1f))
        Metric(label = second.first, value = second.second, modifier = Modifier.weight(1f))
    }
}

@Composable
fun EmptyState(text: String, detail: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceHigh.copy(alpha = 0.46f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            color = AppText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (detail != null) {
            Text(
                text = detail,
                color = AppMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AppText,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.9f),
            style = MaterialTheme.typography.bodySmall,
            color = AppMuted,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun RuntimeActivityLine(entry: RuntimeActivity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceHigh.copy(alpha = 0.52f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.timestamp.timeLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = AppMuted,
                fontFamily = FontFamily.Monospace,
            )
            StatusPill(entry.state.uiLabel(), phase = entry.state)
        }
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        entry.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = AppMuted,
            )
        }
    }
}

@Composable
fun LocalLogLine(entry: LocalLogEntry) {
    val level = entry.level.uppercase()
    val levelColor = when (level) {
        "ERROR" -> AppError
        "WARN", "WARNING" -> AppWarning
        else -> AppAccentAlt
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceHigh.copy(alpha = 0.42f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.timestamp.timeLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = AppMuted,
                fontFamily = FontFamily.Monospace,
            )
            StatusPill(level, color = levelColor.copy(alpha = 0.18f))
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = AppText,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun LatestFollowingLazyColumn(
    itemCount: Int,
    modifier: Modifier = Modifier,
    jumpLabel: String = "Jump to latest",
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    contentPadding: PaddingValues = PaddingValues(),
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    var autoFollowLatest by remember { mutableStateOf(true) }
    val isAtLatest by remember(listState, itemCount) {
        derivedStateOf { listState.isNearLatest(itemCount) }
    }
    val userScrollConnection = remember(listState, itemCount) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (
                    source.isUserInput() &&
                    consumed.y != 0f &&
                    itemCount > 0 &&
                    !listState.isNearLatest(itemCount)
                ) {
                    autoFollowLatest = false
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(itemCount, autoFollowLatest) {
        if (itemCount == 0) {
            autoFollowLatest = true
        } else if (autoFollowLatest) {
            listState.scrollToItem(itemCount - 1)
        }
    }

    LaunchedEffect(itemCount, isAtLatest, autoFollowLatest) {
        if (itemCount == 0 || (isAtLatest && !autoFollowLatest)) {
            autoFollowLatest = true
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(userScrollConnection),
            state = listState,
            verticalArrangement = verticalArrangement,
            contentPadding = contentPadding,
            content = content,
        )
        if (!autoFollowLatest && !isAtLatest && itemCount > 0) {
            AssistChip(
                onClick = { autoFollowLatest = true },
                label = { Text(jumpLabel) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
fun Gap(height: Int = 12) {
    Spacer(modifier = Modifier.height(height.dp))
}

fun Instant?.timeLabel(): String =
    this?.let(TimeFormatter::format) ?: "Never"

fun RuntimePhase.uiLabel(): String = when (this) {
    RuntimePhase.Stopped -> "Stopped"
    RuntimePhase.Connecting -> "Connecting"
    RuntimePhase.Idle -> "Idle"
    RuntimePhase.Fetching -> "Fetching"
    RuntimePhase.Authenticating -> "Authenticating"
    RuntimePhase.LoadingInventory -> "Loading inventory"
    RuntimePhase.SelectingCampaign -> "Selecting campaign"
    RuntimePhase.FindingChannel -> "Finding channel"
    RuntimePhase.Watching -> "Watching"
    RuntimePhase.Claiming -> "Claiming"
    RuntimePhase.Error -> "Error"
}

private fun LazyListState.isNearLatest(itemCount: Int): Boolean {
    if (itemCount == 0) return true
    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    val latestIndex = itemCount - 1
    return lastVisibleIndex >= latestIndex - LatestFollowTrailingItemThreshold
}

private fun NestedScrollSource.isUserInput(): Boolean =
    this == NestedScrollSource.UserInput
