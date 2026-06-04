package com.nathan.twitchdropsminer.android.data.backend

import com.nathan.twitchdropsminer.android.data.model.BackendConsole
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.DropReward
import com.nathan.twitchdropsminer.android.data.model.LoginSession
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.MinerStatus
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class BackendMappers(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {
    fun parseStatus(payload: String): MinerStatus {
        val root = json.parseToJsonElement(payload).jsonObject
        val loginObject = root.objectOrNull("login")
        val loginStatus = loginObject?.stringOrNull("status") ?: "Unknown"
        val oauth = loginObject?.objectOrNull("oauth_pending")
        val manual = root.objectOrNull("manual_mode")
        val statusText = root.stringOrNull("status") ?: "Unknown"

        return MinerStatus(
            statusText = statusText,
            login = LoginSession(
                state = loginStatus.toLoginState(),
                statusText = loginStatus,
                userId = loginObject?.stringOrNull("user_id"),
                oauthUrl = oauth?.stringOrNull("url"),
                oauthCode = oauth?.stringOrNull("code"),
            ),
            manualMode = manual?.booleanOrFalse("active") == true,
            manualGame = manual?.stringOrNull("game_name"),
            manualChannel = manual?.stringOrNull("channel_name"),
            dropsClaimedThisSession = root.intOrZero("drops_claimed_this_session"),
        )
    }

    fun parseCampaigns(payload: String): List<Campaign> {
        val root = json.parseToJsonElement(payload).jsonObject
        return root.arrayOrEmpty("campaigns").mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val drops = obj.arrayOrEmpty("drops").mapNotNull(::parseDrop)
            Campaign(
                id = obj.stringOrNull("id") ?: return@mapNotNull null,
                name = obj.stringOrNull("name") ?: "Untitled campaign",
                gameName = obj.stringOrNull("game_name") ?: "Unknown game",
                gameBoxArtUrl = obj.stringOrNull("game_box_art_url"),
                campaignUrl = obj.stringOrNull("campaign_url"),
                linkUrl = obj.stringOrNull("link_url"),
                startsAt = obj.instantOrNull("starts_at"),
                endsAt = obj.instantOrNull("ends_at"),
                linked = obj.booleanOrFalse("linked"),
                active = obj.booleanOrFalse("active"),
                upcoming = obj.booleanOrFalse("upcoming"),
                expired = obj.booleanOrFalse("expired"),
                claimedDrops = obj.intOrZero("claimed_drops"),
                totalDrops = obj.intOrZero("total_drops"),
                drops = drops,
            )
        }
    }

    fun parseChannels(payload: String): List<Channel> {
        val root = json.parseToJsonElement(payload).jsonObject
        return root.arrayOrEmpty("channels").mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            Channel(
                id = obj.longOrNull("id") ?: return@mapNotNull null,
                name = obj.stringOrNull("name") ?: "Unknown channel",
                game = obj.stringOrNull("game"),
                viewers = obj.intOrNull("viewers"),
                online = obj.booleanOrFalse("online"),
                dropsEnabled = obj.booleanOrFalse("drops_enabled"),
                aclBased = obj.booleanOrFalse("acl_based"),
                watching = obj.booleanOrFalse("watching"),
            )
        }
    }

    fun parseConsole(payload: String): BackendConsole {
        val root = json.parseToJsonElement(payload).jsonObject
        return BackendConsole(
            lines = root.arrayOrEmpty("lines").mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull()
            },
        )
    }

    private fun parseDrop(element: JsonElement): CampaignDrop? {
        val obj = element as? JsonObject ?: return null
        val benefits = obj.arrayOrEmpty("benefits").mapNotNull { benefitElement ->
            val benefit = benefitElement as? JsonObject ?: return@mapNotNull null
            DropReward(
                name = benefit.stringOrNull("name") ?: return@mapNotNull null,
                type = benefit.stringOrNull("type") ?: "UNKNOWN",
                imageUrl = benefit.stringOrNull("image_url"),
                id = benefit.stringOrNull("id"),
            )
        }
        return CampaignDrop(
            id = obj.stringOrNull("id") ?: return null,
            name = obj.stringOrNull("name") ?: "Untitled drop",
            currentMinutes = obj.intOrZero("current_minutes"),
            requiredMinutes = obj.intOrZero("required_minutes"),
            progress = obj.floatOrZero("progress").coerceIn(0f, 1f),
            isClaimed = obj.booleanOrFalse("is_claimed"),
            canClaim = obj.booleanOrFalse("can_claim"),
            rewards = benefits,
            startsAt = obj.instantOrNull("starts_at"),
            endsAt = obj.instantOrNull("ends_at"),
        )
    }
}

private fun String.toLoginState(): LoginState {
    val normalized = lowercase()
    return when {
        "logged in" in normalized -> LoginState.LoggedIn
        "required" in normalized -> LoginState.LoginRequired
        "expired" in normalized || "invalid" in normalized -> LoginState.Expired
        "logged out" in normalized -> LoginState.LoggedOut
        else -> LoginState.Unknown
    }
}

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name) as? JsonObject

private fun JsonObject.arrayOrEmpty(name: String): JsonArray =
    (get(name) as? JsonArray) ?: JsonArray(emptyList())

private fun JsonObject.stringOrNull(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull()

private fun JsonObject.booleanOrFalse(name: String): Boolean =
    stringOrNull(name)?.toBooleanStrictOrNull() ?: false

private fun JsonObject.intOrZero(name: String): Int =
    intOrNull(name) ?: 0

private fun JsonObject.intOrNull(name: String): Int? =
    stringOrNull(name)?.toIntOrNull()

private fun JsonObject.longOrNull(name: String): Long? =
    stringOrNull(name)?.toLongOrNull()

private fun JsonObject.floatOrZero(name: String): Float =
    stringOrNull(name)?.toFloatOrNull() ?: 0f

private fun JsonObject.instantOrNull(name: String): Instant? =
    stringOrNull(name)?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }

private fun JsonPrimitive.contentOrNull(): String? =
    if (isString) content else content
