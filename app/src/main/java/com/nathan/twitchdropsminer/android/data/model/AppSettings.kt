package com.nathan.twitchdropsminer.android.data.model

private const val MinWatchIntervalSeconds = 20
private const val MaxWatchIntervalSeconds = 300
private const val DefaultWatchIntervalSeconds = 59
private const val MinInventoryRefreshMinutes = 15
private const val MaxInventoryRefreshMinutes = 180
private const val DefaultInventoryRefreshMinutes = 60

data class AppSettings(
    val hasCompletedOnboarding: Boolean = false,
    val watchIntervalSeconds: Int = DefaultWatchIntervalSeconds,
    val inventoryRefreshMinutes: Int = DefaultInventoryRefreshMinutes,
    val runInForeground: Boolean = true,
    val keepActiveScreenMode: Boolean = false,
    val useSampleDataFallback: Boolean = false,
    val fallbackToAutoWhenPrioritizedComplete: Boolean = false,
    val fallbackToAutoWhenNoPrioritizedChannel: Boolean = false,
    // Legacy campaign IDs are retained so older saved preferences keep loading.
    val selectedCampaignIds: Set<String> = emptySet(),
    // Legacy unordered game set. selectedGamePriority is the source of truth.
    val selectedGames: Set<String> = emptySet(),
    val selectedGamePriority: List<String> = emptyList(),
    val batteryHelpDismissed: Boolean = false,
    val debugLogging: Boolean = false,
    val advancedBackendMode: Boolean = false,
    val backendUrl: String = "",
    // Kept for compatibility with the optional backend/debug helpers and older tests.
    val pollIntervalSeconds: Int = DefaultWatchIntervalSeconds,
    val monitorInForeground: Boolean = true,
    val sampleMode: Boolean = false,
) {
    val normalizedBackendUrl: String
        get() = backendUrl.trim().trimEnd('/')

    val hasBackend: Boolean
        get() = normalizedBackendUrl.isNotBlank()

    val canConnect: Boolean
        get() = true

    val gamePriorityLabel: String
        get() = if (selectedGamePriority.isEmpty()) {
            "Auto"
        } else {
            "${selectedGamePriority.size} games"
        }

    val hasGamePriority: Boolean
        get() = selectedGamePriority.isNotEmpty()

    fun isGamePrioritized(gameName: String): Boolean =
        selectedGamePriority.any { it.equals(gameName, ignoreCase = true) }

    fun gamePriorityIndex(gameName: String): Int? =
        selectedGamePriority.indexOfFirst { it.equals(gameName, ignoreCase = true) }
            .takeIf { it >= 0 }

    fun allowsCampaign(campaign: Campaign): Boolean =
        !hasGamePriority || isGamePrioritized(campaign.gameName)

    fun isCampaignSelected(campaign: Campaign): Boolean =
        isGamePrioritized(campaign.gameName)

    fun normalized(): AppSettings {
        val normalizedWatchInterval = watchIntervalSeconds.coerceIn(
            MinWatchIntervalSeconds,
            MaxWatchIntervalSeconds,
        )
        val normalizedRefresh = inventoryRefreshMinutes.coerceIn(
            MinInventoryRefreshMinutes,
            MaxInventoryRefreshMinutes,
        )
        val normalizedGamePriority = selectedGamePriority
            .ifEmpty { selectedGames.sortedWith(String.CASE_INSENSITIVE_ORDER) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        return copy(
            backendUrl = normalizedBackendUrl,
            watchIntervalSeconds = normalizedWatchInterval,
            inventoryRefreshMinutes = normalizedRefresh,
            selectedGames = normalizedGamePriority.toSet(),
            selectedGamePriority = normalizedGamePriority,
            pollIntervalSeconds = pollIntervalSeconds.coerceIn(
                MinWatchIntervalSeconds,
                MaxInventoryRefreshMinutes * 60,
            ),
            monitorInForeground = runInForeground,
            sampleMode = useSampleDataFallback,
        )
    }
}
