package com.nathan.twitchdropsminer.android

import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.twitch.CurrentDropProgress
import com.nathan.twitchdropsminer.android.data.twitch.DeviceAuthorization
import com.nathan.twitchdropsminer.android.data.twitch.DropClaimOutcome
import com.nathan.twitchdropsminer.android.data.twitch.DropClaimResult
import com.nathan.twitchdropsminer.android.data.twitch.TokenResponse
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApi
import com.nathan.twitchdropsminer.android.data.twitch.ValidatedToken
import com.nathan.twitchdropsminer.android.runtime.ClaimAttemptTracker
import com.nathan.twitchdropsminer.android.runtime.DropClaimHandler
import com.nathan.twitchdropsminer.android.runtime.DropClaimPreparation
import com.nathan.twitchdropsminer.android.runtime.DropClaimResolver
import com.nathan.twitchdropsminer.android.runtime.RuntimeClaimOutcome
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropClaimRuntimeTest {
    @Test
    fun completedDropWithoutClaimIdGeneratesDropInstanceId() {
        val preparation = DropClaimResolver.prepare(
            session = session(),
            campaign = campaign(),
            drop = completedDrop(claimId = null, canClaim = false),
        )

        val ready = preparation as DropClaimPreparation.Ready
        assertEquals("user-1#campaign-1#drop-1", ready.resolved.claimId)
        assertTrue(ready.resolved.generatedClaimId)
    }

    @Test
    fun incompleteDropIsNotClaimable() {
        val preparation = DropClaimResolver.prepare(
            session = session(),
            campaign = campaign(),
            drop = completedDrop(currentMinutes = 30, requiredMinutes = 60, canClaim = false),
        )

        assertTrue(preparation is DropClaimPreparation.NotClaimable)
    }

    @Test
    fun successfulClaimResultIsCountable() {
        val fakeApi = FakeTwitchApi(DropClaimResult(DropClaimOutcome.Claimed))
        val handler = DropClaimHandler(fakeApi)

        val result = runBlocking {
            handler.claim(session(), campaign(), completedDrop())
        }

        assertEquals(RuntimeClaimOutcome.Claimed, result.outcome)
        assertTrue(result.shouldCountAsNewClaim)
        assertEquals(listOf("claim-1"), fakeApi.claimIds)
    }

    @Test
    fun alreadyClaimedResultIsTerminalButNotCountable() {
        val fakeApi = FakeTwitchApi(DropClaimResult(DropClaimOutcome.AlreadyClaimed))
        val handler = DropClaimHandler(fakeApi)

        val result = runBlocking {
            handler.claim(session(), campaign(), completedDrop())
        }

        assertEquals(RuntimeClaimOutcome.AlreadyClaimed, result.outcome)
        assertTrue(result.isTerminalSuccess)
        assertFalse(result.shouldCountAsNewClaim)
    }

    @Test
    fun failedClaimIsSuppressedUntilCooldownExpires() {
        var now = Instant.parse("2026-06-03T12:00:00Z")
        val fakeApi = FakeTwitchApi(
            DropClaimResult(
                outcome = DropClaimOutcome.Failed,
                twitchStatus = "NOT_ELIGIBLE",
                message = "Not eligible",
            ),
        )
        val handler = DropClaimHandler(
            twitchApi = fakeApi,
            attemptTracker = ClaimAttemptTracker(
                failureCooldown = Duration.ofMinutes(5),
                now = { now },
            ),
        )

        val first = runBlocking { handler.claim(session(), campaign(), completedDrop()) }
        val second = runBlocking { handler.claim(session(), campaign(), completedDrop()) }
        now = now.plus(Duration.ofMinutes(6))
        val third = runBlocking { handler.claim(session(), campaign(), completedDrop()) }

        assertEquals(RuntimeClaimOutcome.Failed, first.outcome)
        assertEquals(RuntimeClaimOutcome.Suppressed, second.outcome)
        assertEquals(RuntimeClaimOutcome.Failed, third.outcome)
        assertEquals(2, fakeApi.claimIds.size)
    }

    private fun session(): StoredTwitchSession =
        StoredTwitchSession(
            accessToken = "token",
            userId = "user-1",
            deviceId = "device-1",
            savedAt = Instant.parse("2026-06-03T00:00:00Z"),
        )

    private fun campaign(): Campaign =
        Campaign(
            id = "campaign-1",
            name = "Campaign",
            gameName = "Game",
            active = true,
            linked = true,
            totalDrops = 1,
            drops = listOf(completedDrop()),
        )

    private fun completedDrop(
        currentMinutes: Int = 60,
        requiredMinutes: Int = 60,
        canClaim: Boolean = true,
        claimId: String? = "claim-1",
    ): CampaignDrop =
        CampaignDrop(
            id = "drop-1",
            name = "Drop",
            currentMinutes = currentMinutes,
            requiredMinutes = requiredMinutes,
            progress = if (requiredMinutes <= 0) 0f else currentMinutes.toFloat() / requiredMinutes,
            isClaimed = false,
            canClaim = canClaim,
            rewards = emptyList(),
            claimId = claimId,
        )
}

private class FakeTwitchApi(
    private val claimResult: DropClaimResult,
) : TwitchApi {
    val claimIds = mutableListOf<String>()

    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization =
        unused()

    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): TokenResponse? =
        unused()

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken =
        unused()

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> =
        unused()

    override suspend fun fetchEligibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        limit: Int,
    ): List<Channel> = unused()

    override suspend fun fetchChannel(
        session: StoredTwitchSession,
        login: String,
        expectedGame: String?,
    ): Channel = unused()

    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean =
        unused()

    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress? =
        unused()

    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult {
        claimIds += dropInstanceId
        return claimResult
    }

    override fun newDeviceId(): String = "device-1"

    private fun <T> unused(): T = error("Not used in this test")
}
