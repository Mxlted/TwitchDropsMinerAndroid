package com.nathan.twitchdropsminer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.nathan.twitchdropsminer.android.data.model.LocalLogEntry
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import kotlinx.coroutines.delay

@Composable
fun LogsScreen(
    localLogs: List<LocalLogEntry>,
    snapshot: RuntimeSnapshot,
    onClearLogs: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
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

    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear local logs?") },
            text = { Text("This removes the persisted Android runtime log. Activity for the current app session remains visible.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        onClearLogs()
                    },
                ) {
                    Text("Clear Logs")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Cancel")
                }
            },
        )
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
                onClick = {
                    clipboard.setText(AnnotatedString(visibleText))
                    copied = true
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (copied) "Copied" else "Copy All")
            }
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = localLogs.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
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
                items(
                    items = visibleLogs,
                    key = { entry -> "${entry.timestamp}-${entry.level}-${entry.message}" },
                ) { entry ->
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
