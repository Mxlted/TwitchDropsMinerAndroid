package com.nathan.twitchdropsminer.android

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import com.nathan.twitchdropsminer.android.data.local.CampaignExclusionIds
import com.nathan.twitchdropsminer.android.data.local.GamePriorityCleanup
import com.nathan.twitchdropsminer.android.data.local.GamePriorityOrder
import com.nathan.twitchdropsminer.android.data.local.SettingsPreferencesMapper
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPersistenceTest {
    @Test
    fun mapsPreferencesAndNormalizesLocalRuntimeSettings() {
        val preferences = preferencesOf(
            SettingsPreferencesMapper.HasCompletedOnboarding to true,
            SettingsPreferencesMapper.WatchIntervalSeconds to 3,
            SettingsPreferencesMapper.InventoryRefreshMinutes to 5,
            SettingsPreferencesMapper.LegacyFallbackToAutoWhenPrioritizedComplete to true,
            SettingsPreferencesMapper.KeepActiveScreenMode to true,
            SettingsPreferencesMapper.ExcludedCampaignIds to setOf(" campaign-2 ", ""),
            SettingsPreferencesMapper.SelectedCampaignIds to setOf("campaign-1"),
            SettingsPreferencesMapper.SelectedGamePriority to """["Game B","Game A"]""",
        )

        val settings = SettingsPreferencesMapper.fromPreferences(preferences)

        assertTrue(settings.hasCompletedOnboarding)
        assertTrue(settings.fallbackToOtherGames)
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
    fun fallbackToOtherGamesDefaultsToOff() {
        val settings = SettingsPreferencesMapper.fromPreferences(preferencesOf())

        assertFalse(settings.fallbackToOtherGames)
    }

    @Test
    fun anyEnabledLegacyFallbackMigratesToUnifiedFallback() {
        val legacyPreferences = listOf(
            SettingsPreferencesMapper.LegacyFallbackToAutoWhenPrioritizedComplete,
            SettingsPreferencesMapper.LegacyFallbackToAutoWhenNoPrioritizedChannel,
            SettingsPreferencesMapper.LegacyAllowWatchingUnlinkedGames,
        )

        legacyPreferences.forEach { legacyKey ->
            val settings = SettingsPreferencesMapper.fromPreferences(
                preferencesOf(legacyKey to true),
            )

            assertTrue(settings.fallbackToOtherGames)
        }
    }

    @Test
    fun savedUnifiedFallbackOverridesLegacyValues() {
        val settings = SettingsPreferencesMapper.fromPreferences(
            preferencesOf(
                SettingsPreferencesMapper.FallbackToOtherGames to false,
                SettingsPreferencesMapper.LegacyAllowWatchingUnlinkedGames to true,
            ),
        )

        assertFalse(settings.fallbackToOtherGames)
    }

    @Test
    fun writingUnifiedFallbackRemovesLegacyKeys() {
        val preferences = mutablePreferencesOf(
            SettingsPreferencesMapper.LegacyFallbackToAutoWhenPrioritizedComplete to true,
            SettingsPreferencesMapper.LegacyFallbackToAutoWhenNoPrioritizedChannel to true,
            SettingsPreferencesMapper.LegacyAllowWatchingUnlinkedGames to true,
        )

        SettingsPreferencesMapper.write(
            preferences,
            AppSettings(fallbackToOtherGames = true),
        )

        assertEquals(true, preferences[SettingsPreferencesMapper.FallbackToOtherGames])
        assertEquals(null, preferences[SettingsPreferencesMapper.LegacyFallbackToAutoWhenPrioritizedComplete])
        assertEquals(null, preferences[SettingsPreferencesMapper.LegacyFallbackToAutoWhenNoPrioritizedChannel])
        assertEquals(null, preferences[SettingsPreferencesMapper.LegacyAllowWatchingUnlinkedGames])
    }

    @Test
    fun resetRestoresDefaultsWhileKeepingOnboardingComplete() {
        val preferences = mutablePreferencesOf(
            SettingsPreferencesMapper.HasCompletedOnboarding to true,
            SettingsPreferencesMapper.WatchIntervalSeconds to 120,
            SettingsPreferencesMapper.FallbackToOtherGames to true,
            SettingsPreferencesMapper.SelectedGamePriority to """["Game"]""",
            SettingsPreferencesMapper.ExcludedCampaignIds to setOf("campaign-1"),
            SettingsPreferencesMapper.DebugLogging to true,
        )

        SettingsPreferencesMapper.reset(preferences)
        val reset = SettingsPreferencesMapper.fromPreferences(preferences)

        assertTrue(reset.hasCompletedOnboarding)
        assertEquals(AppSettings(hasCompletedOnboarding = true), reset)
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
