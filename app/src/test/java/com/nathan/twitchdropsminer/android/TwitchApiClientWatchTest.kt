package com.nathan.twitchdropsminer.android

import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiClient
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchApiClientWatchTest {
    @Test
    fun sendWatchMinutePostsPlainBase64SpadePayload() {
        val server = MockWebServer()
        server.start()
        try {
            val spadeUrl = server.url("/spade").toString()
            server.enqueue(htmlResponse("""<script>window.config={"beacon_url":"$spadeUrl"}</script>"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val client = client(server)

            val accepted = runBlocking { client.sendWatchMinute(session(), channel()) }

            assertTrue(accepted)
            assertEquals("/ExampleChannel", server.takeRequest().path)
            val spadeRequest = server.takeRequest()
            assertEquals("POST", spadeRequest.method)
            assertEquals("/spade", spadeRequest.path)
            assertTrue(spadeRequest.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))

            val form = spadeRequest.body.readUtf8()
            val encoded = URLDecoder.decode(
                form.substringAfter("data="),
                StandardCharsets.UTF_8.name(),
            )
            val event = Json.parseToJsonElement(
                String(Base64.getDecoder().decode(encoded), Charsets.UTF_8),
            ).jsonArray.single().jsonObject
            assertEquals("minute-watched", event["event"]?.jsonPrimitive?.content)
            val properties = event["properties"]!!.jsonObject
            assertEquals("24680", properties["broadcast_id"]?.jsonPrimitive?.content)
            assertEquals("67890", properties["channel_id"]?.jsonPrimitive?.content)
            assertEquals("ExampleChannel", properties["channel"]?.jsonPrimitive?.content)
            assertEquals("Example Game", properties["game"]?.jsonPrimitive?.content)
            assertEquals("13579", properties["game_id"]?.jsonPrimitive?.content)
            assertEquals("channel", properties["location"]?.jsonPrimitive?.content)
            assertEquals("site", properties["player"]?.jsonPrimitive?.content)
            assertEquals("1", properties["minutes_logged"]?.jsonPrimitive?.content)
            assertEquals("12345", properties["user_id"]?.jsonPrimitive?.content)
            assertFalse(properties["user_id"]!!.jsonPrimitive.isString)
            assertTrue(properties["is_live"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(
                properties["client_time"]!!.jsonPrimitive.content
                    .matches(Regex("^\\d{4}-\\d{2}-\\d{2}T.*Z$")),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendWatchMinuteFallsBackToSettingsAssetForSpadeUrl() {
        val server = MockWebServer()
        server.start()
        try {
            val settingsUrl = server.url("/config/settings.${"a".repeat(32)}.js")
            val spadeUrl = server.url("/spade-from-settings")
            server.enqueue(htmlResponse("""<script src="$settingsUrl"></script>"""))
            server.enqueue(htmlResponse("""window.config={"beacon_url":"$spadeUrl"};"""))
            server.enqueue(MockResponse().setResponseCode(204))

            val accepted = runBlocking { client(server).sendWatchMinute(session(), channel()) }

            assertTrue(accepted)
            assertEquals("/ExampleChannel", server.takeRequest().path)
            assertEquals(settingsUrl.encodedPath, server.takeRequest().path)
            assertEquals("/spade-from-settings", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendWatchMinuteCachesResolvedSpadeUrl() {
        val server = MockWebServer()
        server.start()
        try {
            val spadeUrl = server.url("/spade").toString()
            server.enqueue(htmlResponse("""{"beacon_url":"$spadeUrl"}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val client = client(server)

            assertTrue(runBlocking { client.sendWatchMinute(session(), channel()) })
            assertTrue(runBlocking { client.sendWatchMinute(session(), channel()) })

            assertEquals(3, server.requestCount)
            assertEquals("/ExampleChannel", server.takeRequest().path)
            assertEquals("/spade", server.takeRequest().path)
            assertEquals("/spade", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendWatchMinuteReturnsFalseForRejectedSpadePost() {
        val server = MockWebServer()
        server.start()
        try {
            val spadeUrl = server.url("/spade").toString()
            server.enqueue(htmlResponse("""{"beacon_url":"$spadeUrl"}"""))
            server.enqueue(MockResponse().setResponseCode(400))

            assertFalse(runBlocking { client(server).sendWatchMinute(session(), channel()) })
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendWatchMinuteReturnsFalseWhenSpadeUrlIsMissing() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(htmlResponse("<html><body>No watch configuration</body></html>"))

            assertFalse(runBlocking { client(server).sendWatchMinute(session(), channel()) })
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendWatchMinuteReturnsFalseWithoutRequiredStreamMetadata() {
        val server = MockWebServer()
        server.start()
        try {
            val client = client(server)

            assertFalse(
                runBlocking {
                    client.sendWatchMinute(session(), channel().copy(broadcastId = null))
                },
            )
            assertFalse(
                runBlocking {
                    client.sendWatchMinute(session().copy(userId = "not-numeric"), channel())
                },
            )
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun client(server: MockWebServer): TwitchApiClient =
        TwitchApiClient(
            okHttpClient = OkHttpClient(),
            gqlEndpoint = server.url("/gql").toString(),
            twitchWebBaseUrl = server.url("/").toString(),
        )

    private fun session(): StoredTwitchSession =
        StoredTwitchSession(
            accessToken = "token",
            userId = "12345",
            deviceId = "device-1",
            savedAt = Instant.parse("2026-07-11T00:00:00Z"),
        )

    private fun channel(): Channel =
        Channel(
            id = 67890,
            name = "ExampleChannel",
            game = "Example Game",
            gameId = "13579",
            online = true,
            dropsEnabled = true,
            broadcastId = "24680",
        )

    private fun htmlResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/html; charset=utf-8")
            .setBody(body)
}
