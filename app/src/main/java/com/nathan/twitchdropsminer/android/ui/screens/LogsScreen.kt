package com.nathan.twitchdropsminer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.nathan.twitchdropsminer.android.data.model.LocalLogEntry
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot

@Composable
fun LogsScreen(
    localLogs: List<LocalLogEntry>,
    snapshot: RuntimeSnapshot,
    onClearLogs: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val visibleLogs = localLogs.takeLast(120)
    val visibleActivity = snapshot.activity.takeLast(160)
    val listItemCount = 1 +
        (if (visibleLogs.isEmpty()) 1 else visibleLogs.size) +
        1 +
        (if (visibleActivity.isEmpty()) 1 else visibleActivity.size)
    val visibleText = buildString {
        appendLine("Local Android runtime logs")
        localLogs.forEach { appendLine(it.toLine()) }
        if (snapshot.activity.isNotEmpty()) {
            appendLine()
            appendLine("Runtime activity")
            snapshot.activity.forEach { appendLine(it.toLine()) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = "Logs",
            subtitle = "Local app logs plus runtime activity snapshot",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { clipboard.setText(AnnotatedString(visibleText)) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Copy All")
            }
            OutlinedButton(onClick = onClearLogs, modifier = Modifier.weight(1f)) {
                Text("Clear")
            }
        }

        LatestFollowingLazyColumn(
            itemCount = listItemCount,
            modifier = Modifier.weight(1f),
            jumpLabel = "Resume latest",
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionTitle("Local Runtime", "${localLogs.size} lines")
            }
            if (visibleLogs.isEmpty()) {
                item {
                    EmptyState(
                        text = "No local Android logs yet.",
                        detail = "Runtime messages will appear here as the app works.",
                    )
                }
            } else {
                items(visibleLogs) { entry ->
                    LocalLogLine(entry)
                }
            }
            item {
                SectionTitle("Activity Snapshot", "${snapshot.activity.size} events")
            }
            if (visibleActivity.isEmpty()) {
                item {
                    EmptyState(
                        text = "No runtime activity yet.",
                        detail = "Activity appears after login, inventory refresh, or mining.",
                    )
                }
            } else {
                items(visibleActivity) { entry ->
                    RuntimeActivityLine(entry)
                }
            }
        }
    }
}
