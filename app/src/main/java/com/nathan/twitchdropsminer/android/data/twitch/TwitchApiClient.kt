package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.DropReward
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.model.inEarningOrder
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val TwitchClientId = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"
private const val TwitchClientUrl = "https://www.twitch.tv"
private const val TwitchUserAgent =
    "Dalvik/2.1.0 (Linux; U; Android 16; Pixel Build/AP3A.240905.015) tv.twitch.android.app/25.3.0/2503006"
private val JsonMediaType = "application/json; charset=utf-8".toMediaType()

data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAt: Instant,
    val intervalSeconds: Int,
)

data class TokenResponse(
    val accessToken: String,
)

data class ValidatedToken(
    val userId: String,
    val clientId: String,
)

data class CurrentDropProgress(
    val dropId: String,
    val currentMinutes: Int,
)

enum class DropClaimOutcome {
    Claimed,
    AlreadyClaimed,
    Failed,
    MissingDropInstanceId,
    UnexpectedResponse,
}

data class DropClaimResult(
    val outcome: DropClaimOutcome,
    val twitchStatus: String? = null,
    val message: String? = null,
) {
    val isTerminalSuccess: Boolean
        get() = outcome == DropClaimOutcome.Claimed || outcome == DropClaimOutcome.AlreadyClaimed

    val shouldCountAsNewClaim: Boolean
        get() = outcome == DropClaimOutcome.Claimed
}

enum class TwitchApiErrorType {
    InvalidToken,
    Network,
    Http,
    GraphQl,
    UnexpectedResponse,
}

class TwitchApiException(
    val type: TwitchApiErrorType,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

interface TwitchApi {
    suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization
    suspend fun pollDeviceToken(deviceCode: String, deviceId: String): TokenResponse?
    suspend fun validateAccessToken(accessToken: String): ValidatedToken
    suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign>
    suspend fun fetchEligibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        limit: Int = 20,
    ): List<Channel>
    suspend fun fetchChannel(
        session: StoredTwitchSession,
        login: String,
        expectedGame: String? = null,
    ): Channel
    suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean
    suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress?
    suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult
    fun newDeviceId(): String
}

class TwitchApiClient(
    private val okHttpClient: OkHttpClient,
    private val gqlEndpoint: String = "https://gql.twitch.tv/gql",
    private val twitchWebBaseUrl: String = TwitchClientUrl,
) : TwitchApi {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val spadeUrls = ConcurrentHashMap<Long, String>()

    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("client_id", TwitchClientId)
                .add("scopes", "")
                .build()
            val request = Request.Builder()
                .url("https://id.twitch.tv/oauth2/device")
                .headers(baseHeaders(deviceId))
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Twitch device login failed: HTTP ${response.code}")
                }
                val root = response.body?.string()?.asObject()
                    ?: throw IllegalStateException("Twitch device login returned no body")
                val expiresIn = root["expires_in"].asInt(1800)
                DeviceAuthorization(
                    deviceCode = root["device_code"].asString(),
                    userCode = root["user_code"].asString(),
                    verificationUri = root["verification_uri"].asString("https://www.twitch.tv/activate"),
                    expiresAt = Instant.now().plusSeconds(expiresIn.toLong()),
                    intervalSeconds = root["interval"].asInt(5).coerceAtLeast(5),
                )
            }
        }

    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): TokenResponse? =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("client_id", TwitchClientId)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val request = Request.Builder()
                .url("https://id.twitch.tv/oauth2/token")
                .headers(baseHeaders(deviceId))
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 400 || response.code == 428) {
                    return@withContext null
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("Twitch token polling failed: HTTP ${response.code}")
                }
                val root = response.body?.string()?.asObject()
                    ?: throw IllegalStateException("Twitch token response returned no body")
                TokenResponse(root["access_token"].asString())
            }
        }

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://id.twitch.tv/oauth2/validate")
                .header("Authorization", "OAuth $accessToken")
                .get()
                .build()
            val response = try {
                okHttpClient.newCall(request).execute()
            } catch (error: IOException) {
                throw TwitchApiException(
                    TwitchApiErrorType.Network,
                    "Twitch session validation failed: ${error.message ?: "network unavailable"}",
                    error,
                )
            }
            response.use {
                if (it.code == 401 || it.code == 403) {
                    throw TwitchApiException(
                        TwitchApiErrorType.InvalidToken,
                        "Twitch session expired or could not be validated.",
                    )
                }
                if (!it.isSuccessful) {
                    throw TwitchApiException(
                        TwitchApiErrorType.Http,
                        "Twitch session validation failed: HTTP ${it.code}.",
                    )
                }
                val root = it.body?.string()?.asObject()
                    ?: throw IllegalStateException("Twitch validation returned no body")
                ValidatedToken(
                    userId = root["user_id"].asString(),
                    clientId = root["client_id"].asString(),
                )
            }
        }

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> {
        val inventoryResponse = gql(session, TwitchOperation.Inventory.request())
        val inventory = inventoryResponse.path("data", "currentUser", "inventory")
        val inProgress = inventory["dropCampaignsInProgress"].asArray()
        val claimedBenefits = inventory["gameEventDrops"].asArray()
            .mapNotNull { benefit ->
                val obj = benefit.asObjectOrNull() ?: return@mapNotNull null
                val id = obj["id"].asStringOrNull() ?: return@mapNotNull null
                val awardedAt = obj["lastAwardedAt"].asInstantOrNull() ?: return@mapNotNull null
                id to awardedAt
            }
            .toMap()

        val campaignsResponse = gql(session, TwitchOperation.Campaigns.request())
        val available = campaignsResponse.path("data", "currentUser")["dropCampaigns"].asArray()
            .mapNotNull { it.asObjectOrNull() }
            .filter { it["status"].asStringOrNull() in setOf("ACTIVE", "UPCOMING") }
            .map { summary ->
                fetchCampaignDetails(session, summary["id"].asStringOrNull())
                    ?.let { details -> mergeJson(summary, details) }
                    ?: summary
            }
        val merged = mergeCampaignRecords(
            primary = inProgress.mapNotNull { it.asObjectOrNull() },
            secondary = available,
        )

        return merged.mapNotNull { campaign ->
            TwitchCampaignMapper.campaignFromJson(campaign, claimedBenefits)
        }.sortedWith(
            compareByDescending<Campaign> { it.active }
                .thenByDescending { it.linked }
            .thenBy { it.endsAt ?: Instant.MAX },
        )
    }

    private suspend fun fetchCampaignDetails(
        session: StoredTwitchSession,
        campaignId: String?,
    ): JsonObject? {
        if (campaignId.isNullOrBlank()) {
            return null
        }
        return runCatchingCancellable {
            gql(
                session,
                TwitchOperation.CampaignDetails.request(
                    buildJsonObject {
                        put("channelLogin", session.userId)
                        put("dropID", campaignId)
                    },
                ),
            ).path("data", "user")["dropCampaign"].asObjectOrNull()
        }.getOrNullUnlessInvalidToken()
    }

    override suspend fun fetchEligibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        limit: Int,
    ): List<Channel> {
        val allowedChannels = campaign.allowedChannels
            .distinctBy { it.name.lowercase() }
            .take(limit.coerceAtLeast(1))
        if (allowedChannels.isNotEmpty()) {
            // Campaign ACL membership is the strongest eligibility signal Twitch exposes here.
            // Bound the live checks so large allow-lists do not become slow channel scans.
            val attempts = allowedChannels.map { channel ->
                runCatchingCancellable {
                    fetchChannel(session, channel.name, campaign.gameName)
                }
            }
            val resolved = attempts.mapNotNull { it.getOrNullUnlessInvalidToken() }
            if (resolved.isEmpty() && attempts.all { it.isFailure }) {
                throw attempts.firstNotNullOf { it.exceptionOrNull() }
            }
            return resolved.filter { it.online && it.dropsEnabled }
        }

        val slugResponse = gql(
            session,
            TwitchOperation.SlugRedirect.request(
                buildJsonObject { put("name", campaign.gameName) },
            ),
        )
        val slug = slugResponse.path("data", "game")["slug"].asStringOrNull()
            ?: campaign.gameName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        val directoryResponse = gql(
            session,
            TwitchOperation.GameDirectory.request(
                buildJsonObject {
                    put("limit", limit)
                    put("slug", slug)
                    put("imageWidth", 50)
                    put("includeCostreaming", false)
                    putJsonObject("options") {
                        put("sort", "VIEWER_COUNT")
                        put("requestID", "TDM-ANDROID")
                        put("tags", buildJsonArray {})
                        put("freeformTags", JsonNull)
                        put("systemFilters", buildJsonArray { add(JsonPrimitive("DROPS_ENABLED")) })
                        put("broadcasterLanguages", buildJsonArray {})
                        put("includeRestricted", buildJsonArray { add(JsonPrimitive("SUB_ONLY_LIVE")) })
                        putJsonObject("recommendationsContext") { put("platform", "android") }
                    }
                    put("sortTypeIsRecency", false)
                },
            ),
        )

        val streams = directoryResponse.path("data", "game", "streams")["edges"].asArray()
        return streams.mapNotNull { edge ->
            edge.path("node").asObjectOrNull()?.toDirectoryChannel(
                gameName = campaign.gameName,
                dropsEnabled = true,
            )
        }.filter { it.online && it.dropsEnabled }
    }

    override suspend fun fetchChannel(
        session: StoredTwitchSession,
        login: String,
        expectedGame: String?,
    ): Channel {
        val response = gql(
            session,
            TwitchOperation.GetStreamInfo.request(buildJsonObject { put("channel", login) }),
        )
        val user = response.path("data", "user")
        val stream = user["stream"].asObjectOrNull()
            ?: return Channel(
                id = user["id"].asLong(0L),
                name = user["displayName"].asString(login),
                game = expectedGame,
                online = false,
                dropsEnabled = false,
                aclBased = true,
            )
        val settings = user["broadcastSettings"].asObjectOrNull()
        val game = settings?.get("game").asObjectOrNull()
        val actualGameName = game?.get("displayName").asStringOrNull() ?: expectedGame
        val matchesExpectedGame = expectedGame.isNullOrBlank() ||
            actualGameName?.equals(expectedGame, ignoreCase = true) == true
        return Channel(
            id = user["id"].asLong(0L),
            name = user["displayName"].asString(login),
            game = actualGameName,
            gameId = game?.get("id").asStringOrNull(),
            viewers = stream["viewersCount"].asIntOrNull(),
            online = true,
            dropsEnabled = matchesExpectedGame,
            aclBased = true,
            broadcastId = stream["id"].asStringOrNull(),
            title = settings?.get("title").asStringOrNull(),
        )
    }

    override suspend fun sendWatchMinute(
        session: StoredTwitchSession,
        channel: Channel,
    ): Boolean = withContext(Dispatchers.IO) {
        val broadcastId = channel.broadcastId?.takeIf { it.isNotBlank() }
            ?: return@withContext false
        val userId = session.userId.toLongOrNull()
            ?: return@withContext false
        val spadeUrl = spadeUrls[channel.id]
            ?: resolveSpadeUrl(session, channel)?.also { spadeUrls[channel.id] = it }
            ?: return@withContext false
        val encodedPayload = encodeWatchPayload(
            userId = userId,
            channel = channel,
            broadcastId = broadcastId,
        )
        val request = Request.Builder()
            .url(spadeUrl)
            .headers(sessionHeaders(session))
            .post(FormBody.Builder().add("data", encodedPayload).build())
            .build()
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (error: IOException) {
            throw TwitchApiException(
                TwitchApiErrorType.Network,
                "Twitch watch-event network failure: ${error.message ?: "network unavailable"}",
                error,
            )
        }
        response.use {
            if (it.code == 401 || it.code == 403) {
                throw TwitchApiException(
                    TwitchApiErrorType.InvalidToken,
                    "Twitch session expired or is not authorized to report watch activity.",
                )
            }
            it.code == 204
        }
    }

    override suspend fun currentDrop(
        session: StoredTwitchSession,
        channelId: Long,
    ): CurrentDropProgress? {
        val response = gql(
            session,
            TwitchOperation.CurrentDrop.request(
                buildJsonObject {
                    put("channelID", channelId.toString())
                    put("channelLogin", "")
                },
            ),
        )
        val drop = response.path("data", "currentUser")["dropCurrentSession"].asObjectOrNull()
            ?: return null
        return CurrentDropProgress(
            dropId = drop["dropID"].asString(),
            currentMinutes = drop["currentMinutesWatched"].asInt(0),
        )
    }

    override suspend fun claimDrop(
        session: StoredTwitchSession,
        dropInstanceId: String,
    ): DropClaimResult {
        if (dropInstanceId.isBlank()) {
            return DropClaimResult(
                outcome = DropClaimOutcome.MissingDropInstanceId,
                message = "Drop instance ID is missing.",
            )
        }
        val response = gql(
            session,
            TwitchOperation.ClaimDrop.request(
                buildJsonObject {
                    putJsonObject("input") {
                        put("dropInstanceID", dropInstanceId)
                    }
                },
            ),
        )
        val data = response["data"].asObjectOrNull()
            ?: return DropClaimResult(
                outcome = DropClaimOutcome.UnexpectedResponse,
                message = "Twitch claim response did not contain a data object.",
            )
        val dataErrors = data["errors"].asArray()
        if (dataErrors.isNotEmpty()) {
            return DropClaimResult(
                outcome = DropClaimOutcome.Failed,
                message = "Twitch returned claim errors: $dataErrors",
            )
        }
        val claimRewards = data["claimDropRewards"]
        if (claimRewards == null || claimRewards is JsonNull) {
            return DropClaimResult(
                outcome = DropClaimOutcome.Failed,
                message = "Twitch did not return claimDropRewards.",
            )
        }
        val status = claimRewards.asObjectOrNull()?.get("status").asStringOrNull()
            ?: return DropClaimResult(
                outcome = DropClaimOutcome.UnexpectedResponse,
                message = "Twitch claim response did not include a status.",
            )
        return when (status) {
            "ELIGIBLE_FOR_ALL" -> DropClaimResult(DropClaimOutcome.Claimed, twitchStatus = status)
            "DROP_INSTANCE_ALREADY_CLAIMED" -> DropClaimResult(
                DropClaimOutcome.AlreadyClaimed,
                twitchStatus = status,
                message = "Drop was already claimed.",
            )
            else -> DropClaimResult(
                outcome = DropClaimOutcome.Failed,
                twitchStatus = status,
                message = "Twitch claim operation returned status $status.",
            )
        }
    }

    override fun newDeviceId(): String = UUID.randomUUID().toString().replace("-", "")

    private suspend fun gql(
        session: StoredTwitchSession,
        payload: JsonObject,
    ): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(gqlEndpoint)
            .headers(sessionHeaders(session))
            .post(payload.toString().toRequestBody(JsonMediaType))
            .build()
        try {
            okHttpClient.newCall(request).execute()
        } catch (error: IOException) {
            throw TwitchApiException(
                TwitchApiErrorType.Network,
                "Twitch GraphQL network failure: ${error.message ?: "network unavailable"}",
                error,
            )
        }.use { response ->
            if (response.code == 401 || response.code == 403) {
                throw TwitchApiException(
                    TwitchApiErrorType.InvalidToken,
                    "Twitch session expired or is not authorized for this request.",
                )
            }
            if (!response.isSuccessful) {
                throw TwitchApiException(
                    TwitchApiErrorType.Http,
                    "Twitch GraphQL failed: HTTP ${response.code}",
                )
            }
            val body = response.body?.string()
                ?: throw TwitchApiException(
                    TwitchApiErrorType.UnexpectedResponse,
                    "Twitch GraphQL returned no body",
                )
            val root = runCatching { json.parseToJsonElement(body) }.getOrElse { error ->
                throw TwitchApiException(
                    TwitchApiErrorType.UnexpectedResponse,
                    "Twitch GraphQL returned invalid JSON.",
                    error,
                )
            }
            val first = if (root is JsonArray) root.firstOrNull() else root
            val obj = first as? JsonObject
                ?: throw TwitchApiException(
                    TwitchApiErrorType.UnexpectedResponse,
                    "Unexpected Twitch GraphQL response shape.",
                )
            if ("errors" in obj) {
                throw TwitchApiException(
                    TwitchApiErrorType.GraphQl,
                    "Twitch GraphQL error: ${obj["errors"]}",
                )
            }
            obj
        }
    }

    private fun baseHeaders(deviceId: String): okhttp3.Headers =
        okhttp3.Headers.Builder()
            .add("Accept", "application/json")
            .add("Client-Id", TwitchClientId)
            .add("Origin", TwitchClientUrl)
            .add("Referer", TwitchClientUrl)
            .add("User-Agent", TwitchUserAgent)
            .add("X-Device-Id", deviceId)
            .build()

    private fun sessionHeaders(session: StoredTwitchSession): okhttp3.Headers =
        okhttp3.Headers.Builder()
            .add("Accept", "*/*")
            .add("Client-Id", TwitchClientId)
            .add("Authorization", "OAuth ${session.accessToken}")
            .add("Client-Session-Id", session.deviceId.take(16))
            .add("Origin", TwitchClientUrl)
            .add("Referer", TwitchClientUrl)
            .add("User-Agent", TwitchUserAgent)
            .add("X-Device-Id", session.deviceId)
            .build()

    private fun encodeWatchPayload(
        userId: Long,
        channel: Channel,
        broadcastId: String,
    ): String {
        val payload = buildJsonArray {
            add(
                buildJsonObject {
                    put("event", "minute-watched")
                    putJsonObject("properties") {
                        put("broadcast_id", broadcastId)
                        put("channel_id", channel.id.toString())
                        put("channel", channel.name)
                        put("client_time", Instant.now().toString())
                        put("game", channel.game.orEmpty())
                        put("game_id", channel.gameId.orEmpty())
                        put("hidden", false)
                        put("is_live", true)
                        put("live", true)
                        put("location", "channel")
                        put("logged_in", true)
                        put("minutes_logged", 1)
                        put("muted", false)
                        put("player", "site")
                        put("user_id", userId)
                    }
                },
            )
        }.toString()
        return Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    private fun resolveSpadeUrl(
        session: StoredTwitchSession,
        channel: Channel,
    ): String? {
        val channelUrl = twitchWebBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment(channel.name)
            .build()
        val channelHtml = getWatchConfiguration(session, channelUrl.toString())
        SpadeUrlPattern.find(channelHtml)?.groupValues?.get(1)?.let { candidate ->
            return candidate.takeIf(::isAllowedWatchConfigurationUrl)
        }
        val settingsUrl = SettingsUrlPattern.find(channelHtml)?.groupValues?.get(1)
            ?.takeIf(::isAllowedWatchConfigurationUrl)
            ?: return null
        val settings = getWatchConfiguration(session, settingsUrl)
        return SpadeUrlPattern.find(settings)?.groupValues?.get(1)
            ?.takeIf(::isAllowedWatchConfigurationUrl)
    }

    private fun getWatchConfiguration(
        session: StoredTwitchSession,
        url: String,
    ): String {
        val request = Request.Builder()
            .url(url)
            .headers(sessionHeaders(session))
            .get()
            .build()
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (error: IOException) {
            throw TwitchApiException(
                TwitchApiErrorType.Network,
                "Twitch watch configuration failed: ${error.message ?: "network unavailable"}",
                error,
            )
        }
        return response.use {
            if (it.code == 401 || it.code == 403) {
                throw TwitchApiException(
                    TwitchApiErrorType.InvalidToken,
                    "Twitch session expired while loading watch configuration.",
                )
            }
            if (!it.isSuccessful) {
                throw TwitchApiException(
                    TwitchApiErrorType.Http,
                    "Twitch watch configuration failed: HTTP ${it.code}.",
                )
            }
            it.body?.string().orEmpty()
        }
    }

    private fun isAllowedWatchConfigurationUrl(candidate: String): Boolean {
        val url = candidate.toHttpUrlOrNull() ?: return false
        if (url.isHttps) return true
        return url.host == "localhost" || url.host == "127.0.0.1" || url.host == "::1"
    }

    private fun String.asObject(): JsonObject =
        json.parseToJsonElement(this).jsonObject
}

private val SpadeUrlPattern =
    Regex("\\\"beacon_?url\\\"\\s*:\\s*\\\"(https?://[^\\\"]+)\\\"", RegexOption.IGNORE_CASE)
private val SettingsUrlPattern = Regex(
    "src=[\\\"'](https?://[^\\\"']+/config/settings\\.[0-9a-f]{32}\\.js)[\\\"']",
    RegexOption.IGNORE_CASE,
)

internal object TwitchCampaignMapper {
    fun campaignFromJson(
        campaign: JsonObject,
        claimedBenefits: Map<String, Instant>,
    ): Campaign? =
        runCatching { campaign.toCampaign(claimedBenefits) }.getOrNull()
}

enum class TwitchOperation(
    val operationName: String,
    val sha256Hash: String,
    val defaultVariables: JsonObject = buildJsonObject {},
) {
    GetStreamInfo(
        "VideoPlayerStreamInfoOverlayChannel",
        "198492e0857f6aedead9665c81c5a06d67b25b58034649687124083ff288597d",
    ),
    ClaimDrop(
        "DropsPage_ClaimDropRewards",
        "a455deea71bdc9015b78eb49f4acfbce8baa7ccbedd28e549bb025bd0f751930",
    ),
    Inventory(
        "Inventory",
        "d86775d0ef16a63a33ad52e80eaff963b2d5b72fada7c991504a57496e1d8e4b",
        buildJsonObject { put("fetchRewardCampaigns", false) },
    ),
    CurrentDrop(
        "DropCurrentSessionContext",
        "4d06b702d25d652afb9ef835d2a550031f1cf762b193523a92166f40ea3d142b",
    ),
    Campaigns(
        "ViewerDropsDashboard",
        "5a4da2ab3d5b47c9f9ce864e727b2cb346af1e3ea8b897fe8f704a97ff017619",
        buildJsonObject { put("fetchRewardCampaigns", false) },
    ),
    CampaignDetails(
        "DropCampaignDetails",
        "039277bf98f3130929262cc7c6efd9c141ca3749cb6dca442fc8ead9a53f77c1",
    ),
    GameDirectory(
        "DirectoryPage_Game",
        "cb5dc816e139dcb8a118f14b4b677d59abc224a4b016c4bc2bb00a47fe0ddec4",
    ),
    SlugRedirect(
        "DirectoryGameRedirect",
        "1f0300090caceec51f33c5e20647aceff9017f740f223c3c532ba6fa59f6b6cc",
    );

    fun request(variables: JsonObject = defaultVariables): JsonObject =
        buildJsonObject {
            put("operationName", operationName)
            put("variables", variables)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", sha256Hash)
                }
            }
        }
}

private fun JsonObject.toCampaign(claimedBenefits: Map<String, Instant>): Campaign {
    val now = Instant.now()
    val game = this["game"].asObjectOrNull()
    val drops = this["timeBasedDrops"].asArray()
        .mapNotNull { drop -> drop.asObjectOrNull()?.toCampaignDrop(claimedBenefits) }
        .inEarningOrder()
    val startsAt = this["startAt"].asInstantOrNull()
    val endsAt = this["endAt"].asInstantOrNull()
    val status = this["status"].asStringOrNull()
    val linkUrl = this["accountLinkURL"].asStringOrNull()
    val self = this["self"].asObjectOrNull()
    val linked = self?.get("isAccountConnected").asBool(false)
    val linkStatusKnown = self?.containsKey("isAccountConnected") == true || linkUrl != null
    val allowedChannels = this.path("allow")["channels"].asArray()
        .mapNotNull { it.asObjectOrNull()?.toAllowedChannel() }
    return Campaign(
        id = this["id"].asString(),
        name = this["name"].asString("Unnamed campaign"),
        gameName = game?.get("displayName").asStringOrNull()
            ?: game?.get("name").asStringOrNull()
            ?: "Unknown game",
        gameBoxArtUrl = game?.get("boxArtURL").asStringOrNull(),
        campaignUrl = "https://www.twitch.tv/drops/campaigns?dropID=${this["id"].asString()}",
        linkUrl = linkUrl,
        startsAt = startsAt,
        endsAt = endsAt,
        linked = linked,
        linkStatusKnown = linkStatusKnown,
        active = status == "ACTIVE" || (startsAt != null && endsAt != null && now >= startsAt && now < endsAt),
        upcoming = status == "UPCOMING" || (startsAt != null && now < startsAt),
        expired = status == "EXPIRED" || (endsAt != null && now >= endsAt),
        claimedDrops = drops.count { it.isClaimed },
        totalDrops = drops.size,
        drops = drops,
        allowedChannels = allowedChannels,
    )
}

private fun mergeJson(primary: JsonObject, secondary: JsonObject): JsonObject =
    JsonObject(
        (primary.keys + secondary.keys).associateWith { key ->
            val primaryValue = primary[key]
            val secondaryValue = secondary[key]
            if (primaryValue is JsonObject && secondaryValue is JsonObject) {
                mergeJson(primaryValue, secondaryValue)
            } else {
                primaryValue ?: secondaryValue ?: JsonNull
            }
        },
    )

private fun mergeCampaignRecords(
    primary: List<JsonObject>,
    secondary: List<JsonObject>,
): List<JsonObject> {
    val records = linkedMapOf<String, JsonObject>()
    secondary.forEach { campaign ->
        val id = campaign["id"].asStringOrNull() ?: return@forEach
        records[id] = campaign
    }
    primary.forEach { campaign ->
        val id = campaign["id"].asStringOrNull() ?: return@forEach
        records[id] = records[id]?.let { existing -> mergeJson(campaign, existing) } ?: campaign
    }
    return records.values.toList()
}

private fun JsonObject.toCampaignDrop(claimedBenefits: Map<String, Instant>): CampaignDrop {
    val benefits = this["benefitEdges"].asArray().mapNotNull { benefit ->
        benefit.asObjectOrNull()?.toDropReward()
    }
    val startsAt = this["startAt"].asInstantOrNull()
    val endsAt = this["endAt"].asInstantOrNull()
    val currentMinutes = this.path("self")["currentMinutesWatched"].asInt(0)
    val requiredMinutes = this["requiredMinutesWatched"].asInt(0)
    val self = this["self"].asObjectOrNull()
    val claimId = self?.get("dropInstanceID").asStringOrNull()
    val claimedBySelf = self?.get("isClaimed").asBool(false)
    val claimedBenefitAwardTimes = benefits.mapNotNull { reward ->
        reward.id?.let(claimedBenefits::get)
    }
    val claimedByBenefit = self == null &&
        startsAt != null &&
        endsAt != null &&
        claimedBenefitAwardTimes.isNotEmpty() &&
        claimedBenefitAwardTimes.all { awardedAt -> awardedAt >= startsAt && awardedAt < endsAt }
    val isClaimed = claimedBySelf || claimedByBenefit
    val displayedMinutes = if (isClaimed) requiredMinutes else currentMinutes
    return CampaignDrop(
        id = this["id"].asString(),
        name = this["name"].asString("Drop"),
        currentMinutes = displayedMinutes,
        requiredMinutes = requiredMinutes,
        progress = if (requiredMinutes <= 0) 0f else (displayedMinutes.toFloat() / requiredMinutes).coerceIn(0f, 1f),
        isClaimed = isClaimed,
        canClaim = !isClaimed && requiredMinutes > 0 && currentMinutes >= requiredMinutes,
        rewards = benefits,
        startsAt = startsAt,
        endsAt = endsAt,
        claimId = claimId,
        preconditionDropIds = this["preconditionDrops"].asArray()
            .mapNotNull { it.asObjectOrNull()?.get("id").asStringOrNull() },
    )
}

private fun JsonObject.toDropReward(): DropReward =
    DropReward(
        name = this.path("benefit")["name"].asString(this["name"].asString("Reward")),
        type = this.path("benefit")["distributionType"].asString(
            this.path("benefit")["type"].asString(this["type"].asString("UNKNOWN")),
        ),
        imageUrl = this.path("benefit")["imageAssetURL"].asStringOrNull()
            ?: this["imageAssetURL"].asStringOrNull(),
        id = this.path("benefit")["id"].asStringOrNull(),
    )

private fun JsonObject.toAllowedChannel(): Channel =
    Channel(
        id = this["id"].asLong(0L),
        name = this["login"].asString(
            this["name"].asString(this["displayName"].asString("channel")),
        ),
        aclBased = true,
    )

private fun JsonObject.toDirectoryChannel(gameName: String, dropsEnabled: Boolean): Channel {
    val broadcaster = this["broadcaster"].asObjectOrNull()
    val game = this["game"].asObjectOrNull()
    return Channel(
        id = broadcaster?.get("id").asLong(0L),
        name = broadcaster?.get("displayName").asString(
            broadcaster?.get("login").asString("streamer"),
        ),
        game = game?.get("displayName").asStringOrNull() ?: gameName,
        gameId = game?.get("id").asStringOrNull(),
        viewers = this["viewersCount"].asIntOrNull(),
        online = true,
        dropsEnabled = dropsEnabled,
        broadcastId = this["id"].asStringOrNull(),
        title = this["title"].asStringOrNull(),
    )
}

private fun JsonElement?.path(vararg keys: String): JsonObject {
    var current: JsonElement? = this
    for (key in keys) {
        current = current.asObjectOrNull()?.get(key)
    }
    return current.asObjectOrNull() ?: buildJsonObject {}
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asArray(): List<JsonElement> =
    (this as? JsonArray)?.toList() ?: emptyList()

private fun JsonElement?.asString(default: String = ""): String =
    asStringOrNull() ?: default

private fun JsonElement?.asStringOrNull(): String? =
    this?.jsonPrimitive?.contentOrNull

private fun JsonElement?.asInt(default: Int): Int =
    asIntOrNull() ?: default

private fun JsonElement?.asIntOrNull(): Int? =
    this?.jsonPrimitive?.intOrNull

private fun JsonElement?.asLong(default: Long): Long =
    asStringOrNull()?.toLongOrNull() ?: default

private fun JsonElement?.asBool(default: Boolean): Boolean =
    this?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonElement?.asInstantOrNull(): Instant? =
    asStringOrNull()?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }

@Suppress("unused")
private fun JsonElement?.asFloat(default: Float): Float =
    this?.jsonPrimitive?.floatOrNull ?: default

private suspend inline fun <T> runCatchingCancellable(
    crossinline block: suspend () -> T,
): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

private fun <T> Result<T>.getOrNullUnlessInvalidToken(): T? =
    fold(
        onSuccess = { it },
        onFailure = { error ->
            if (error is TwitchApiException && error.type == TwitchApiErrorType.InvalidToken) {
                throw error
            }
            null
        },
    )
