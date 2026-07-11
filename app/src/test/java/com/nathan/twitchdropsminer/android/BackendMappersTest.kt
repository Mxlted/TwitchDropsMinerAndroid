package com.nathan.twitchdropsminer.android

import com.nathan.twitchdropsminer.android.data.backend.BackendMappers
import com.nathan.twitchdropsminer.android.data.model.LoginState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendMappersTest {
    private val mapper = BackendMappers()

    @Test
    fun parsesStatusPayload() {
        val status = mapper.parseStatus(
            """
            {
              "status": "Watching test_channel",
              "login": {"status": "Logged in", "user_id": 12345},
              "manual_mode": {"active": true, "game_name": "Test Game", "channel_name": "test_channel"},
              "drops_claimed_this_session": 2
            }
            """.trimIndent(),
        )

        assertEquals("Watching test_channel", status.statusText)
        assertEquals(LoginState.LoggedIn, status.login.state)
        assertEquals("12345", status.login.userId)
        assertTrue(status.manualMode)
        assertEquals(2, status.dropsClaimedThisSession)
    }

    @Test
    fun parsesCampaignsPayload() {
        val campaigns = mapper.parseCampaigns(
            """
            {
              "campaigns": [
                {
                  "id": "campaign-1",
                  "name": "Campaign",
                  "game_name": "Game",
                  "active": true,
                  "claimed_drops": 1,
                  "total_drops": 2,
                  "drops": [
                    {
                      "id": "drop-1",
                      "name": "Drop",
                      "current_minutes": 10,
                      "required_minutes": 20,
                      "progress": 0.5,
                      "is_claimed": false,
                      "can_claim": false,
                      "benefits": [{"name": "Badge", "type": "BADGE"}]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, campaigns.size)
        assertEquals("Game", campaigns.first().gameName)
        assertFalse(campaigns.first().linkStatusKnown)
        assertEquals(0.5f, campaigns.first().progress, 0.001f)
        assertEquals("Badge", campaigns.first().drops.first().rewards.first().name)
    }

    @Test
    fun parsesChannelsPayload() {
        val channels = mapper.parseChannels(
            """
            {
              "channels": [
                {
                  "id": 99,
                  "name": "streamer",
                  "game": "Game",
                  "viewers": 100,
                  "online": true,
                  "drops_enabled": true,
                  "acl_based": false,
                  "watching": true
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, channels.size)
        assertEquals(99L, channels.first().id)
        assertTrue(channels.first().watching)
    }
}
