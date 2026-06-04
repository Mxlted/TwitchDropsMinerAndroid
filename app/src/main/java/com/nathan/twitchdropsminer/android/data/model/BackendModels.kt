package com.nathan.twitchdropsminer.android.data.model

import java.time.Instant

enum class LoginState {
    Unknown,
    LoggedOut,
    LoginRequired,
    LoggedIn,
    Expired,
}

data class LoginSession(
    val state: LoginState = LoginState.Unknown,
    val statusText: String = "Unknown",
    val userId: String? = null,
    val oauthUrl: String? = null,
    val oauthCode: String? = null,
    val deviceCode: String? = null,
    val expiresAt: Instant? = null,
) {
    val isAuthenticated: Boolean
        get() = state == LoginState.LoggedIn

    val isActionRequired: Boolean
        get() = state == LoginState.LoginRequired || oauthCode != null
}

data class StoredTwitchSession(
    val accessToken: String,
    val userId: String,
    val deviceId: String,
    val savedAt: Instant,
)

data class MinerStatus(
    val statusText: String,
    val login: LoginSession,
    val manualMode: Boolean = false,
    val manualGame: String? = null,
    val manualChannel: String? = null,
    val dropsClaimedThisSession: Int = 0,
)

data class DropReward(
    val name: String,
    val type: String,
    val imageUrl: String? = null,
    val id: String? = null,
)

data class CampaignDrop(
    val id: String,
    val name: String,
    val currentMinutes: Int,
    val requiredMinutes: Int,
    val progress: Float,
    val isClaimed: Boolean,
    val canClaim: Boolean,
    val rewards: List<DropReward>,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val claimId: String? = null,
    val preconditionDropIds: List<String> = emptyList(),
) {
    val remainingMinutes: Int
        get() = (requiredMinutes - currentMinutes).coerceAtLeast(0)

    val hasCompletedProgress: Boolean
        get() = requiredMinutes > 0 && currentMinutes >= requiredMinutes
}

data class Campaign(
    val id: String,
    val name: String,
    val gameName: String,
    val gameBoxArtUrl: String? = null,
    val campaignUrl: String? = null,
    val linkUrl: String? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val linked: Boolean = false,
    val active: Boolean = false,
    val upcoming: Boolean = false,
    val expired: Boolean = false,
    val claimedDrops: Int = 0,
    val totalDrops: Int = 0,
    val drops: List<CampaignDrop> = emptyList(),
    val allowedChannels: List<Channel> = emptyList(),
    val selected: Boolean = false,
) {
    val progress: Float
        get() = if (drops.isEmpty()) {
            0f
        } else {
            drops.map { it.progress.coerceIn(0f, 1f) }.average().toFloat()
        }

    val statusLabel: String
        get() = when {
            active -> "Active"
            upcoming -> "Upcoming"
            expired -> "Expired"
            else -> "Unknown"
        }

    val remainingMinutes: Int
        get() = drops.sumOf { it.remainingMinutes }

    val canEarnLocally: Boolean
        get() = active && linked && drops.any {
            !it.isClaimed && (it.remainingMinutes > 0 || it.canClaim || it.hasCompletedProgress)
        }
}

data class Channel(
    val id: Long,
    val name: String,
    val game: String? = null,
    val viewers: Int? = null,
    val online: Boolean = false,
    val dropsEnabled: Boolean = false,
    val aclBased: Boolean = false,
    val watching: Boolean = false,
    val broadcastId: String? = null,
    val gameId: String? = null,
    val title: String? = null,
) {
    val statusLabel: String
        get() = when {
            watching -> "Watching"
            online && dropsEnabled -> "Drops enabled"
            online -> "Online"
            else -> "Offline"
        }
}

data class BackendConsole(
    val lines: List<String> = emptyList(),
)
