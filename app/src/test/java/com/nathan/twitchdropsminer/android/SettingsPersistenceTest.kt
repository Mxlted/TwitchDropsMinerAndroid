package com.nathan.twitchdropsminer.android

import androidx.datastore.preferences.core.preferencesOf
import com.nathan.twitchdropsminer.android.data.local.CampaignExclusionIds
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.local.GamePriorityCleanup
import com.nathan.twitchdropsminer.android.data.local.GamePriorityOrder
import com.nathan.twitchdropsminer.android.data.local.SettingsPreferencesMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPersistenceTest {
    @Test
    fun mapsPreferencesAndNormalizesLocalRuntimeSettings() {
        val preferences = preferencesOf(
            SettingsPreferencesMapper.HasCompletedOnboarding to true,
            SettingsPreferencesMapper.WatchIntervalSeconds to 3,
            SettingsPreferencesMapper.InventoryRefreshMinutes to 5,
            SettingsPreferencesMapper.FallbackToAutoWhenPrioritizedComplete to true,
            SettingsPreferencesMapper.FallbackToAutoWhenNoPrioritizedChannel to true,
            SettingsPreferencesMapper.AllowWatchingUnlinkedGames to true,
            SettingsPreferencesMapper.KeepActiveScreenMode to true,
            SettingsPreferencesMapper.ExcludedCampaignIds to setOf(" campaign-2 ", ""),
            SettingsPreferencesMapper.SelectedCampaignIds to setOf("campaign-1"),
            SettingsPreferencesMapper.SelectedGamePriority to """["Game B","Game A"]""",
        )

        val settings = SettingsPreferencesMapper.fromPreferences(preferences)

        assertTrue(settings.hasCompletedOnboarding)
        assertTrue(settings.fallbackToAutoWhenPrioritizedComplete)
        assertTrue(settings.fallbackToAutoWhenNoPrioritizedChannel)
        assertTrue(settings.allowWatchingUnlinkedGames)
        assertTrue(settings.keepActiveScreenMode)
        assertEquals(20, settings.watchIntervalSeconds)
        assertEquals(15, settings.inventoryRefreshMinutes)
        assertEquals(setOf("campaign-2"), settings.excludedCampaignIds)
        assertEquals(setOf("campaign-1"), settings.selectedCampaignIds)
        assertEquals(listOf("Game B", "Game A"), settings.selectedGamePriority)
        assertEquals(setOf("Game B", "Game A"), settings.selectedGames)
    }

    @Test
    fun normalizesExcludedCampaignIds() {
        val settings = AppSettings(
            excludedCampaignIds = setOf(" campaign-1 ", "CAMPAIGN-1", ""),
        ).normalized()

        assertEquals(setOf("campaign-1"), settings.excludedCampaignIds)
    }

    @Test
    fun campaignExclusionIdsAddRemoveAndClearCaseInsensitively() {
        val added = CampaignExclusionIds.update(
            current = setOf("existing-campaign"),
            campaignIds = listOf(" Campaign-1 ", "CAMPAIGN-1", ""),
            excluded = true,
        )

        assertEquals(setOf("existing-campaign", "Campaign-1"), added)

        val removed = CampaignExclusionIds.update(
            current = added,
            campaignIds = listOf("campaign-1"),
            excluded = false,
        )

        assertEquals(setOf("existing-campaign"), removed)
        assertEquals(
            emptySet<String>(),
            CampaignExclusionIds.update(removed, removed, excluded = false),
        )
    }

    @Test
    fun migratesLegacySelectedGamesIntoStablePriorityOrder() {
        val preferences = preferencesOf(
            SettingsPreferencesMapper.SelectedGames to setOf("Zulu Game", "Alpha Game"),
        )

        val settings = SettingsPreferencesMapper.fromPreferences(preferences)

        assertEquals(listOf("Alpha Game", "Zulu Game"), settings.selectedGamePriority)
    }

    @Test
    fun malformedSavedGamePriorityJsonFallsBackToEmptyList() {
        val preferences = preferencesOf(
            SettingsPreferencesMapper.SelectedGamePriority to "not-valid-json",
        )

        val settings = SettingsPreferencesMapper.fromPreferences(preferences)

        assertEquals(emptyList<String>(), settings.selectedGamePriority)
        assertEquals(emptySet<String>(), settings.selectedGames)
    }

    @Test
    fun unlinkedWatchingDefaultsToOptIn() {
        val settings = SettingsPreferencesMapper.fromPreferences(preferencesOf())

        assertEquals(false, settings.allowWatchingUnlinkedGames)
    }

    @Test
    fun manualPriorityNumbersClampAndReorderSequentially() {
        val priority = listOf("Alpha", "Bravo", "Charlie", "Delta")

        assertEquals(
            listOf("Charlie", "Alpha", "Bravo", "Delta"),
            GamePriorityOrder.set(priority, "Charlie", 1),
        )
        assertEquals(
            listOf("Alpha", "Charlie", "Delta", "Bravo"),
            GamePriorityOrder.set(priority, "Bravo", 99),
        )
        assertEquals(
            listOf("Delta", "Alpha", "Bravo", "Charlie"),
            GamePriorityOrder.set(priority, "Delta", 0),
        )
    }

    @Test
    fun manualPriorityNumbersRemoveDuplicateGameConflicts() {
        val priority = listOf("Alpha", "Bravo", "alpha", "Charlie")

        assertEquals(
            listOf("Bravo", "Alpha", "Charlie"),
            GamePriorityOrder.set(priority, "Alpha", 2),
        )
    }

    @Test
    fun priorityCleanupRemovesGamesWithoutAvailableCampaigns() {
        val cleanup = GamePriorityCleanup.removeUnavailable(
            current = listOf("Alpha", "Bravo", "alpha", "Charlie"),
            availableGameNames = listOf("bravo", "Charlie"),
        )

        assertEquals(listOf("Bravo", "Charlie"), cleanup.retainedPriority)
        assertEquals(listOf("Alpha"), cleanup.removedGames)
    }
}
