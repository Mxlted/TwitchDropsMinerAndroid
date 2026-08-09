package com.nathan.twitchdropsminer.android.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.AutoModePriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "android_app_settings",
)

class SettingsRepository(
    context: Context,
) {
    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> =
        dataStore.data.map(SettingsPreferencesMapper::fromPreferences)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            val current = SettingsPreferencesMapper.fromPreferences(preferences)
            SettingsPreferencesMapper.write(preferences, transform(current).normalized())
        }
    }

    suspend fun completeOnboarding() {
        update { it.copy(hasCompletedOnboarding = true) }
    }

    suspend fun resetSessionSettings() {
        update {
            it.copy(
                selectedCampaignIds = emptySet(),
                selectedGames = emptySet(),
                selectedGamePriority = emptyList(),
                excludedCampaignIds = emptySet(),
                runInForeground = true,
                monitorInForeground = true,
            )
        }
    }

    suspend fun resetSettings() {
        dataStore.edit(SettingsPreferencesMapper::reset)
    }

    suspend fun toggleCampaignSelection(campaignId: String) {
        update { settings ->
            val selected = settings.selectedCampaignIds.toMutableSet()
            if (!selected.add(campaignId)) {
                selected.remove(campaignId)
            }
            settings.copy(selectedCampaignIds = selected)
        }
    }

    suspend fun clearCampaignSelections() {
        update {
            it.copy(
                selectedCampaignIds = emptySet(),
                selectedGames = emptySet(),
                selectedGamePriority = emptyList(),
            )
        }
    }

    suspend fun setCampaignExclusion(campaignIds: Collection<String>, excluded: Boolean) {
        update { settings ->
            settings.copy(
                excludedCampaignIds = CampaignExclusionIds.update(
                    current = settings.excludedCampaignIds,
                    campaignIds = campaignIds,
                    excluded = excluded,
                ),
            )
        }
    }

    suspend fun toggleGamePriority(gameName: String) {
        update { settings ->
            val selected = GamePriorityOrder.toggle(settings.selectedGamePriority, gameName)
            if (selected == settings.selectedGamePriority) {
                settings
            } else {
                settings.withGamePriority(selected)
            }
        }
    }

    suspend fun moveGamePriority(gameName: String, offset: Int) {
        if (offset == 0) {
            return
        }
        val normalized = gameName.trim()
        update { settings ->
            val selected = GamePriorityOrder.move(settings.selectedGamePriority, normalized, offset)
            if (selected == settings.selectedGamePriority) {
                settings
            } else {
                settings.withGamePriority(selected)
            }
        }
    }

    suspend fun setGamePriority(gameName: String, priorityNumber: Int) {
        update { settings ->
            val selected = GamePriorityOrder.set(settings.selectedGamePriority, gameName, priorityNumber)
            if (selected == settings.selectedGamePriority) {
                settings
            } else {
                settings.withGamePriority(selected)
            }
        }
    }

    suspend fun clearGamePriority() {
        update {
            it.copy(
                selectedCampaignIds = emptySet(),
                selectedGames = emptySet(),
                selectedGamePriority = emptyList(),
            )
        }
    }

    suspend fun moveAutoModePriority(option: AutoModePriority, offset: Int) {
        if (offset == 0) {
            return
        }
        update { settings ->
            settings.copy(
                autoModePriorityOrder = AutoModePriorityOrder.move(
                    current = settings.autoModePriorityOrder,
                    option = option,
                    offset = offset,
                ),
            )
        }
    }

    suspend fun removeGamePrioritiesWithoutAvailableCampaigns(
        availableGameNames: Collection<String>,
    ): List<String> {
        var removedGames = emptyList<String>()
        update { settings ->
            val cleanup = GamePriorityCleanup.removeUnavailable(
                current = settings.selectedGamePriority,
                availableGameNames = availableGameNames,
            )
            removedGames = cleanup.removedGames
            if (cleanup.removedGames.isEmpty()) {
                settings
            } else {
                settings.withGamePriority(cleanup.retainedPriority)
            }
        }
        return removedGames
    }
}

internal object GamePriorityOrder {
    fun toggle(current: List<String>, gameName: String): List<String> {
        val normalized = gameName.trim()
        val selected = normalizedPriority(current).toMutableList()
        if (normalized.isBlank()) {
            return selected
        }
        val existingIndex = selected.indexOfGame(normalized)
        if (existingIndex >= 0) {
            selected.removeAt(existingIndex)
        } else {
            selected.add(normalized)
        }
        return selected
    }

    fun move(current: List<String>, gameName: String, offset: Int): List<String> {
        val selected = normalizedPriority(current).toMutableList()
        if (offset == 0) {
            return selected
        }
        val currentIndex = selected.indexOfGame(gameName)
        if (currentIndex < 0) {
            return selected
        }
        val targetIndex = (currentIndex + offset).coerceIn(0, selected.lastIndex)
        if (targetIndex == currentIndex) {
            return selected
        }
        val moved = selected.removeAt(currentIndex)
        selected.add(targetIndex, moved)
        return selected
    }

    fun set(current: List<String>, gameName: String, priorityNumber: Int): List<String> {
        val normalized = gameName.trim()
        val selected = normalizedPriority(current).toMutableList()
        if (normalized.isBlank()) {
            return selected
        }
        val existingIndex = selected.indexOfGame(normalized)
        val gameLabel = if (existingIndex >= 0) {
            selected.removeAt(existingIndex)
        } else {
            normalized
        }
        val targetIndex = (priorityNumber.coerceAtLeast(1) - 1).coerceIn(0, selected.size)
        selected.add(targetIndex, gameLabel)
        return selected
    }

    private fun normalizedPriority(current: List<String>): List<String> =
        current
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

    private fun List<String>.indexOfGame(gameName: String): Int =
        indexOfFirst { it.equals(gameName, ignoreCase = true) }
}

internal object AutoModePriorityOrder {
    fun move(
        current: List<AutoModePriority>,
        option: AutoModePriority,
        offset: Int,
    ): List<AutoModePriority> {
        val ordered = AutoModePriority.normalize(current).toMutableList()
        if (offset == 0) {
            return ordered
        }
        val currentIndex = ordered.indexOf(option)
        val targetIndex = (currentIndex + offset).coerceIn(0, ordered.lastIndex)
        if (currentIndex == targetIndex) {
            return ordered
        }
        ordered.add(targetIndex, ordered.removeAt(currentIndex))
        return ordered
    }
}

internal data class GamePriorityCleanupResult(
    val retainedPriority: List<String>,
    val removedGames: List<String>,
)

internal object GamePriorityCleanup {
    fun removeUnavailable(
        current: List<String>,
        availableGameNames: Collection<String>,
    ): GamePriorityCleanupResult {
        val available = availableGameNames
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        val normalizedPriority = current
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        val retained = normalizedPriority.filter { it.lowercase() in available }
        return GamePriorityCleanupResult(
            retainedPriority = retained,
            removedGames = normalizedPriority.filterNot { it.lowercase() in available },
        )
    }
}

internal object CampaignExclusionIds {
    fun update(
        current: Set<String>,
        campaignIds: Collection<String>,
        excluded: Boolean,
    ): Set<String> {
        val normalizedCurrent = normalize(current)
        val normalizedIds = normalize(campaignIds)
        if (normalizedIds.isEmpty()) {
            return normalizedCurrent
        }
        val targetKeys = normalizedIds.map { it.lowercase() }.toSet()
        val retained = normalizedCurrent.filterNot { it.lowercase() in targetKeys }
        return if (excluded) {
            normalize(retained + normalizedIds)
        } else {
            retained.toSet()
        }
    }

    fun normalize(ids: Collection<String>): Set<String> =
        ids
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .toSet()
}

private fun AppSettings.withGamePriority(selected: List<String>): AppSettings =
    copy(
        selectedCampaignIds = emptySet(),
        selectedGames = selected.toSet(),
        selectedGamePriority = selected,
    )

object SettingsPreferencesMapper {
    private val SettingsJson = Json {
        ignoreUnknownKeys = true
    }

    val HasCompletedOnboarding = booleanPreferencesKey("has_completed_onboarding")
    val BackendUrl = stringPreferencesKey("backend_url")
    val PollIntervalSeconds = intPreferencesKey("poll_interval_seconds")
    val WatchIntervalSeconds = intPreferencesKey("watch_interval_seconds")
    val InventoryRefreshMinutes = intPreferencesKey("inventory_refresh_minutes")
    val MonitorInForeground = booleanPreferencesKey("monitor_in_foreground")
    val RunInForeground = booleanPreferencesKey("run_in_foreground")
    val KeepActiveScreenMode = booleanPreferencesKey("keep_active_screen_mode")
    val FallbackToOtherGames = booleanPreferencesKey("fallback_to_other_games")
    val AutoModePriorityOrder = stringPreferencesKey("auto_mode_priority_order")
    val LegacyFallbackToAutoWhenPrioritizedComplete =
        booleanPreferencesKey("fallback_to_auto_when_prioritized_complete")
    val LegacyFallbackToAutoWhenNoPrioritizedChannel =
        booleanPreferencesKey("fallback_to_auto_when_no_prioritized_channel")
    val LegacyAllowWatchingUnlinkedGames = booleanPreferencesKey("allow_watching_unlinked_games")
    val ExcludedCampaignIds = stringSetPreferencesKey("excluded_campaign_ids")
    val SelectedCampaignIds = stringSetPreferencesKey("selected_campaign_ids")
    val SelectedGames = stringSetPreferencesKey("selected_games")
    val SelectedGamePriority = stringPreferencesKey("selected_game_priority")
    val DebugLogging = booleanPreferencesKey("debug_logging")
    val AdvancedBackendMode = booleanPreferencesKey("advanced_backend_mode")

    fun fromPreferences(preferences: Preferences): AppSettings =
        AppSettings(
            hasCompletedOnboarding = preferences[HasCompletedOnboarding] ?: false,
            backendUrl = preferences[BackendUrl] ?: "",
            pollIntervalSeconds = preferences[PollIntervalSeconds] ?: 60,
            watchIntervalSeconds = preferences[WatchIntervalSeconds]
                ?: preferences[PollIntervalSeconds]
                ?: 59,
            inventoryRefreshMinutes = preferences[InventoryRefreshMinutes] ?: 60,
            monitorInForeground = preferences[MonitorInForeground] ?: true,
            runInForeground = preferences[RunInForeground]
                ?: preferences[MonitorInForeground]
                ?: true,
            keepActiveScreenMode = preferences[KeepActiveScreenMode] ?: false,
            fallbackToOtherGames = preferences[FallbackToOtherGames]
                ?: legacyFallbackEnabled(preferences),
            autoModePriorityOrder = decodeAutoModePriorityOrder(preferences[AutoModePriorityOrder]),
            excludedCampaignIds = preferences[ExcludedCampaignIds] ?: emptySet(),
            selectedCampaignIds = preferences[SelectedCampaignIds] ?: emptySet(),
            selectedGames = preferences[SelectedGames] ?: emptySet(),
            selectedGamePriority = decodeGamePriority(preferences[SelectedGamePriority]),
            debugLogging = preferences[DebugLogging] ?: false,
            advancedBackendMode = preferences[AdvancedBackendMode] ?: false,
        ).normalized()

    fun write(preferences: MutablePreferences, settings: AppSettings) {
        preferences[HasCompletedOnboarding] = settings.hasCompletedOnboarding
        preferences[BackendUrl] = settings.normalizedBackendUrl
        preferences[PollIntervalSeconds] = settings.pollIntervalSeconds
        preferences[WatchIntervalSeconds] = settings.watchIntervalSeconds
        preferences[InventoryRefreshMinutes] = settings.inventoryRefreshMinutes
        preferences[MonitorInForeground] = settings.monitorInForeground
        preferences[RunInForeground] = settings.runInForeground
        preferences[KeepActiveScreenMode] = settings.keepActiveScreenMode
        preferences[FallbackToOtherGames] = settings.fallbackToOtherGames
        preferences[AutoModePriorityOrder] = SettingsJson.encodeToString(
            settings.autoModePriorityOrder.map(AutoModePriority::storageKey),
        )
        preferences.remove(LegacyFallbackToAutoWhenPrioritizedComplete)
        preferences.remove(LegacyFallbackToAutoWhenNoPrioritizedChannel)
        preferences.remove(LegacyAllowWatchingUnlinkedGames)
        preferences[ExcludedCampaignIds] = settings.excludedCampaignIds
        preferences[SelectedCampaignIds] = settings.selectedCampaignIds
        preferences[SelectedGames] = settings.selectedGames
        preferences[SelectedGamePriority] = SettingsJson.encodeToString(settings.selectedGamePriority)
        preferences[DebugLogging] = settings.debugLogging
        preferences[AdvancedBackendMode] = settings.advancedBackendMode
    }

    fun reset(preferences: MutablePreferences) {
        val hasCompletedOnboarding = preferences[HasCompletedOnboarding] ?: false
        preferences.clear()
        write(
            preferences,
            AppSettings(hasCompletedOnboarding = hasCompletedOnboarding).normalized(),
        )
    }

    private fun legacyFallbackEnabled(preferences: Preferences): Boolean =
        preferences[LegacyFallbackToAutoWhenPrioritizedComplete] == true ||
            preferences[LegacyFallbackToAutoWhenNoPrioritizedChannel] == true ||
            preferences[LegacyAllowWatchingUnlinkedGames] == true

    private fun decodeGamePriority(value: String?): List<String> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching {
            SettingsJson.decodeFromString<List<String>>(value)
        }.getOrDefault(emptyList())
    }

    private fun decodeAutoModePriorityOrder(value: String?): List<AutoModePriority> {
        if (value.isNullOrBlank()) {
            return AutoModePriority.DefaultOrder
        }
        return runCatching {
            SettingsJson.decodeFromString<List<String>>(value)
                .mapNotNull(AutoModePriority::fromStorageKey)
        }.getOrDefault(AutoModePriority.DefaultOrder)
    }
}
