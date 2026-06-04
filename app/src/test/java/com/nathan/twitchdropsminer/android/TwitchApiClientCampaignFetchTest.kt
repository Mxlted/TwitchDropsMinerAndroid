package com.nathan.twitchdropsminer.android

import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiClient
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchApiClientCampaignFetchTest {
    @Test
    fun fetchCampaignsExpandsActiveDashboardCampaignsWithDetails() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(jsonResponse(inventoryPayload()))
            server.enqueue(jsonResponse(dashboardPayload()))
            server.enqueue(jsonResponse(campaignDetailsPayload()))

            val client = TwitchApiClient(
                okHttpClient = OkHttpClient(),
                gqlEndpoint = server.url("/gql").toString(),
            )

            val campaigns = runBlocking {
                client.fetchCampaigns(session())
            }

            assertEquals(1, campaigns.size)
            val campaign = campaigns.single()
            assertEquals("campaign-available", campaign.id)
            assertEquals("Farmable Game", campaign.gameName)
            assertEquals(1, campaign.totalDrops)
            assertTrue(campaign.active)
            assertTrue(campaign.canEarnLocally)
            assertEquals("Fresh Drop", campaign.drops.single().name)

            val requestBodies = List(3) { server.takeRequest().body.readUtf8() }
            assertTrue(requestBodies[0].contains("\"operationName\":\"Inventory\""))
            assertTrue(requestBodies[1].contains("\"operationName\":\"ViewerDropsDashboard\""))
            assertTrue(requestBodies[2].contains("\"operationName\":\"DropCampaignDetails\""))
            assertTrue(requestBodies[2].contains("\"dropID\":\"campaign-available\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aclChannelStreamingDifferentGameIsNotEligible() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(jsonResponse(streamInfoPayload(gameName = "Other Game")))
            val client = TwitchApiClient(
                okHttpClient = OkHttpClient(),
                gqlEndpoint = server.url("/gql").toString(),
            )

            val channels = runBlocking {
                client.fetchEligibleChannels(
                    session = session(),
                    campaign = Campaign(
                        id = "campaign-1",
                        name = "Campaign",
                        gameName = "Target Game",
                        allowedChannels = listOf(Channel(id = 1, name = "allowed_channel")),
                    ),
                )
            }

            assertEquals(emptyList<Channel>(), channels)
            assertTrue(server.takeRequest().body.readUtf8().contains("VideoPlayerStreamInfoOverlayChannel"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun allowedCampaignChannelIsChosenBeforeDirectoryDiscovery() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(jsonResponse(streamInfoPayload(gameName = "Target Game")))
            val client = TwitchApiClient(
                okHttpClient = OkHttpClient(),
                gqlEndpoint = server.url("/gql").toString(),
            )

            val channels = runBlocking {
                client.fetchEligibleChannels(
                    session = session(),
                    campaign = Campaign(
                        id = "campaign-1",
                        name = "Campaign",
                        gameName = "Target Game",
                        allowedChannels = listOf(Channel(id = 1, name = "allowed_channel")),
                    ),
                )
            }

            assertEquals(1, channels.size)
            assertEquals("allowed_channel", channels.single().name)
            assertTrue(channels.single().aclBased)
            assertEquals(1, server.requestCount)
            assertTrue(server.takeRequest().body.readUtf8().contains("VideoPlayerStreamInfoOverlayChannel"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unrestrictedChannelDiscoveryUsesDropsEnabledDirectoryFilter() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(jsonResponse(slugRedirectPayload()))
            server.enqueue(jsonResponse(directoryPayload()))
            val client = TwitchApiClient(
                okHttpClient = OkHttpClient(),
                gqlEndpoint = server.url("/gql").toString(),
            )

            val channels = runBlocking {
                client.fetchEligibleChannels(
                    session = session(),
                    campaign = Campaign(
                        id = "campaign-1",
                        name = "Campaign",
                        gameName = "Target Game",
                    ),
                )
            }

            assertEquals(1, channels.size)
            assertEquals("drops_streamer", channels.single().name)
            assertEquals(true, channels.single().dropsEnabled)

            val requestBodies = List(2) { server.takeRequest().body.readUtf8() }
            assertTrue(requestBodies[0].contains("\"operationName\":\"DirectoryGameRedirect\""))
            assertTrue(requestBodies[1].contains("\"operationName\":\"DirectoryPage_Game\""))
            assertTrue(requestBodies[1].contains("DROPS_ENABLED"))
        } finally {
            server.shutdown()
        }
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun session(): StoredTwitchSession =
        StoredTwitchSession(
            accessToken = "token",
            userId = "user-1",
            deviceId = "device-1",
            savedAt = Instant.parse("2026-06-03T00:00:00Z"),
        )

    private fun inventoryPayload(): String =
        """
        {
          "data": {
            "currentUser": {
              "inventory": {
                "dropCampaignsInProgress": [],
                "gameEventDrops": []
              }
            }
          }
        }
        """.trimIndent()

    private fun dashboardPayload(): String =
        """
        {
          "data": {
            "currentUser": {
              "dropCampaigns": [
                {
                  "id": "campaign-available",
                  "name": "Dashboard Summary",
                  "status": "ACTIVE",
                  "startAt": "2020-01-01T00:00:00Z",
                  "endAt": "2099-01-01T00:00:00Z",
                  "accountLinkURL": null,
                  "game": {
                    "id": "game-1",
                    "displayName": "Farmable Game",
                    "boxArtURL": "https://static-cdn.jtvnw.net/game.jpg"
                  },
                  "self": {"isAccountConnected": true},
                  "allow": {"channels": []}
                }
              ]
            }
          }
        }
        """.trimIndent()

    private fun campaignDetailsPayload(): String =
        """
        {
          "data": {
            "user": {
              "dropCampaign": {
                "id": "campaign-available",
                "name": "Full Campaign Details",
                "status": "ACTIVE",
                "startAt": "2020-01-01T00:00:00Z",
                "endAt": "2099-01-01T00:00:00Z",
                "accountLinkURL": null,
                "game": {
                  "id": "game-1",
                  "displayName": "Farmable Game",
                  "boxArtURL": "https://static-cdn.jtvnw.net/game.jpg"
                },
                "self": {"isAccountConnected": true},
                "timeBasedDrops": [
                  {
                    "id": "drop-1",
                    "name": "Fresh Drop",
                    "startAt": "2020-01-01T00:00:00Z",
                    "endAt": "2099-01-01T00:00:00Z",
                    "requiredMinutesWatched": 60,
                    "benefitEdges": [
                      {
                        "benefit": {
                          "id": "benefit-1",
                          "name": "Reward",
                          "distributionType": "DIRECT_ENTITLEMENT"
                        }
                      }
                    ],
                    "preconditionDrops": []
                  }
                ],
                "allow": {"channels": []}
              }
            }
          }
        }
        """.trimIndent()

    private fun slugRedirectPayload(): String =
        """
        {
          "data": {
            "game": {
              "slug": "target-game"
            }
          }
        }
        """.trimIndent()

    private fun directoryPayload(): String =
        """
        {
          "data": {
            "game": {
              "streams": {
                "edges": [
                  {
                    "node": {
                      "id": "broadcast-2",
                      "title": "Drops enabled stream",
                      "viewersCount": 450,
                      "broadcaster": {
                        "id": "67890",
                        "login": "drops_streamer",
                        "displayName": "drops_streamer"
                      },
                      "game": {
                        "id": "game-1",
                        "displayName": "Target Game"
                      }
                    }
                  }
                ]
              }
            }
          }
        }
        """.trimIndent()

    private fun streamInfoPayload(gameName: String): String =
        """
        {
          "data": {
            "user": {
              "id": "12345",
              "displayName": "allowed_channel",
              "stream": {
                "id": "broadcast-1",
                "viewersCount": 120
              },
              "broadcastSettings": {
                "title": "Live now",
                "game": {
                  "id": "game-2",
                  "displayName": "$gameName"
                }
              }
            }
          }
        }
        """.trimIndent()
}
