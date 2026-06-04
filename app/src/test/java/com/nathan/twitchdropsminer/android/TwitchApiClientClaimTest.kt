package com.nathan.twitchdropsminer.android

import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.twitch.DropClaimOutcome
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiClient
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchApiClientClaimTest {
    @Test
    fun claimDropSendsPersistedClaimOperation() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(jsonResponse("""{"data":{"claimDropRewards":{"status":"ELIGIBLE_FOR_ALL"}}}"""))
            val client = TwitchApiClient(
                okHttpClient = OkHttpClient(),
                gqlEndpoint = server.url("/gql").toString(),
            )

            val result = runBlocking {
                client.claimDrop(session(), "user-1#campaign-1#drop-1")
            }

            assertEquals(DropClaimOutcome.Claimed, result.outcome)
            assertTrue(result.shouldCountAsNewClaim)
            val requestBody = server.takeRequest().body.readUtf8()
            assertTrue(requestBody.contains("\"operationName\":\"DropsPage_ClaimDropRewards\""))
            assertTrue(requestBody.contains("\"dropInstanceID\":\"user-1#campaign-1#drop-1\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun alreadyClaimedResponseIsTerminalButNotNewClaim() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                jsonResponse("""{"data":{"claimDropRewards":{"status":"DROP_INSTANCE_ALREADY_CLAIMED"}}}"""),
            )
            val client = TwitchApiClient(
                okHttpClient = OkHttpClient(),
                gqlEndpoint = server.url("/gql").toString(),
            )

            val result = runBlocking {
                client.claimDrop(session(), "user-1#campaign-1#drop-1")
            }

            assertEquals(DropClaimOutcome.AlreadyClaimed, result.outcome)
            assertTrue(result.isTerminalSuccess)
            assertFalse(result.shouldCountAsNewClaim)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun failedStatusIsReportedClearly() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(jsonResponse("""{"data":{"claimDropRewards":{"status":"NOT_ELIGIBLE"}}}"""))
            val client = TwitchApiClient(
                okHttpClient = OkHttpClient(),
                gqlEndpoint = server.url("/gql").toString(),
            )

            val result = runBlocking {
                client.claimDrop(session(), "user-1#campaign-1#drop-1")
            }

            assertEquals(DropClaimOutcome.Failed, result.outcome)
            assertEquals("NOT_ELIGIBLE", result.twitchStatus)
            assertTrue(result.message.orEmpty().contains("NOT_ELIGIBLE"))
        } finally {
            server.shutdown()
        }
    }

    private fun session(): StoredTwitchSession =
        StoredTwitchSession(
            accessToken = "token",
            userId = "user-1",
            deviceId = "device-1",
            savedAt = Instant.parse("2026-06-03T00:00:00Z"),
        )

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
