package com.nathan.twitchdropsminer.android.runtime

import com.nathan.twitchdropsminer.android.data.model.BackendConsole
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.MinerStatus
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import java.time.Instant

object MinerRuntimeReducer {
    fun reduce(
        status: MinerStatus,
        campaigns: List<Campaign>,
        channels: List<Channel>,
        console: BackendConsole,
        now: Instant = Instant.now(),
    ): RuntimeSnapshot {
        val watching = channels.firstOrNull { it.watching }
        val currentTask = when {
            watching != null -> "Watching ${watching.name}"
            status.manualMode && status.manualChannel != null -> "Manual mode: ${status.manualChannel}"
            else -> status.statusText.ifBlank { "Backend connected" }
        }

        return RuntimeSnapshot(
            phase = phaseFor(status.statusText, watching != null),
            account = status.login,
            currentTask = currentTask,
            progressSummary = progressSummary(campaigns),
            lastUpdate = now,
            campaigns = campaigns,
            channels = channels,
            backendConsole = console.lines,
            dropsClaimedThisSession = status.dropsClaimedThisSession,
            error = null,
        )
    }

    fun error(
        message: String,
        previous: RuntimeSnapshot = RuntimeSnapshot(),
        now: Instant = Instant.now(),
    ): RuntimeSnapshot =
        previous.copy(
            phase = RuntimePhase.Error,
            currentTask = "Backend unavailable",
            lastUpdate = now,
            error = message,
        )

    private fun phaseFor(statusText: String, hasWatchingChannel: Boolean): RuntimePhase {
        val normalized = statusText.lowercase()
        return when {
            hasWatchingChannel || "watching" in normalized -> RuntimePhase.Watching
            "fetch" in normalized || "gather" in normalized || "reload" in normalized ->
                RuntimePhase.Fetching
            "idle" in normalized || "no campaign" in normalized || "no channel" in normalized ->
                RuntimePhase.Idle
            "exit" in normalized || "terminated" in normalized || "stopped" in normalized ->
                RuntimePhase.Stopped
            else -> RuntimePhase.Connecting
        }
    }

    private fun progressSummary(campaigns: List<Campaign>): String {
        if (campaigns.isEmpty()) {
            return "No campaigns loaded"
        }
        val active = campaigns.count { it.active }
        val claimed = campaigns.sumOf { it.claimedDrops }
        val total = campaigns.sumOf { it.totalDrops }.coerceAtLeast(1)
        val percent = ((claimed.toFloat() / total.toFloat()) * 100).toInt()
        return "$active active campaigns, $claimed/$total drops claimed ($percent%)"
    }
}
