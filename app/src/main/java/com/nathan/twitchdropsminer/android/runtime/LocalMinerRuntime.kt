package com.nathan.twitchdropsminer.android.runtime

import com.nathan.twitchdropsminer.android.data.local.LogRepository
import com.nathan.twitchdropsminer.android.data.local.SecureSessionStore
import com.nathan.twitchdropsminer.android.data.local.SettingsRepository
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.LoginSession
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.RuntimeActivity
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApi
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalMinerRuntime(
    private val settingsRepository: SettingsRepository,
    private val secureSessionStore: SecureSessionStore,
    private val logRepository: LogRepository,
    private val twitchApiClient: TwitchApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(
        RuntimeSnapshot(
            phase = RuntimePhase.Stopped,
            currentTask = "Local miner stopped",
            progressSummary = "No local inventory loaded",
        ),
    )

    private var miningJob: Job? = null
    private var authJob: Job? = null
    private var dropsClaimedThisSession = 0
    private val dropClaimHandler = DropClaimHandler(twitchApiClient)

    val snapshot: StateFlow<RuntimeSnapshot> = _snapshot

    suspend fun bootstrap() {
        val session = secureSessionStore.twitchSession()
        _snapshot.update {
            it.copy(
                account = if (session == null) {
                    LoginSession(LoginState.LoggedOut, "Twitch login required")
                } else {
                    LoginSession(
                        state = LoginState.LoggedIn,
                        statusText = "Stored Twitch session",
                        userId = session.userId,
                    )
                },
                lastUpdate = Instant.now(),
            )
        }
    }

    fun startAuthentication() {
        if (authJob?.isActive == true) {
            return
        }
        authJob = scope.launch {
            mutex.withLock {
                val existingDeviceId = secureSessionStore.twitchSession()?.deviceId
                val deviceId = existingDeviceId ?: twitchApiClient.newDeviceId()
                try {
                    appendActivity(
                        RuntimePhase.Authenticating,
                        "Starting Twitch device login",
                        "No Twitch password is collected by this app.",
                    )
                    val authorization = twitchApiClient.requestDeviceCode(deviceId)
                    updateSnapshot(
                        RuntimePhase.Authenticating,
                        "Waiting for Twitch activation",
                    ) {
                        it.copy(
                            account = LoginSession(
                                state = LoginState.LoginRequired,
                                statusText = "Enter Twitch device code ${authorization.userCode}",
                                oauthUrl = authorization.verificationUri,
                                oauthCode = authorization.userCode,
                                deviceCode = authorization.deviceCode,
                                expiresAt = authorization.expiresAt,
                            ),
                            progressSummary = "Open Twitch activation and approve the device code.",
                        )
                    }

                    while (isActive && Instant.now().isBefore(authorization.expiresAt)) {
                        delay(authorization.intervalSeconds * 1000L)
                        val token = twitchApiClient.pollDeviceToken(
                            authorization.deviceCode,
                            deviceId,
                        ) ?: continue
                        val validated = twitchApiClient.validateAccessToken(token.accessToken)
                        val session = StoredTwitchSession(
                            accessToken = token.accessToken,
                            userId = validated.userId,
                            deviceId = deviceId,
                            savedAt = Instant.now(),
                        )
                        secureSessionStore.saveTwitchSession(session)
                        updateSnapshot(RuntimePhase.Idle, "Twitch login complete") {
                            it.copy(
                                account = LoginSession(
                                    state = LoginState.LoggedIn,
                                    statusText = "Logged in with Twitch",
                                    userId = validated.userId,
                                ),
                                progressSummary = "Ready to load drops inventory.",
                                error = null,
                            )
                        }
                        appendActivity(RuntimePhase.Idle, "Twitch session saved securely")
                        return@withLock
                    }
                    updateSnapshot(RuntimePhase.Error, "Twitch device code expired") {
                        it.copy(
                            account = LoginSession(LoginState.LoginRequired, "Device code expired"),
                            error = "Twitch device code expired. Start login again.",
                        )
                    }
                } catch (error: Throwable) {
                    updateSnapshot(RuntimePhase.Error, "Twitch login failed") {
                        it.copy(
                            account = LoginSession(LoginState.LoginRequired, "Login failed"),
                            error = error.message ?: "Twitch login failed",
                        )
                    }
                    appendActivity(
                        RuntimePhase.Error,
                        "Twitch login failed",
                        error.message,
                    )
                }
            }
        }
    }

    fun startMining() {
        if (miningJob?.isActive == true) {
            return
        }
        dropClaimHandler.clearAttempts()
        miningJob = scope.launch {
            appendActivity(RuntimePhase.LoadingInventory, "Local miner started")
            try {
                runMiningLoop()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                updateSnapshot(RuntimePhase.Error, "Local miner stopped after an unexpected error") {
                    it.copy(
                        currentChannel = null,
                        error = error.message ?: "Unexpected local miner failure.",
                    )
                }
                appendActivity(
                    RuntimePhase.Error,
                    "Local miner stopped after unexpected error",
                    error.message,
                )
            }
        }
    }

    fun stopMining() {
        scope.launch {
            stopMiningAndJoin()
        }
    }

    suspend fun stopMiningAndJoin() {
        val job = miningJob
        job?.cancelAndJoin()
        if (miningJob == job) {
            miningJob = null
        }
        updateSnapshot(RuntimePhase.Stopped, "Local miner stopped") {
            it.copy(currentChannel = null, activeCampaign = null, activeDrop = null)
        }
        appendActivity(RuntimePhase.Stopped, "Local miner stopped")
    }

    suspend fun refreshInventoryOnce() {
        val settings = settingsRepository.settings.first()
        val session = secureSessionStore.twitchSession()
        val campaigns = loadCampaigns(settings, session)
        updateSnapshot(RuntimePhase.Idle, "Inventory refreshed") {
            it.copy(
                campaigns = markSelected(campaigns, settings),
                channels = it.channels,
                progressSummary = campaigns.progressSummary(),
                error = null,
            )
        }
        appendActivity(RuntimePhase.Idle, "Inventory refreshed", "${campaigns.size} campaigns")
    }

    fun toggleGamePriority(gameName: String) {
        scope.launch {
            settingsRepository.toggleGamePriority(gameName)
            val settings = settingsRepository.settings.first()
            _snapshot.update {
                it.copy(
                    campaigns = markSelected(it.campaigns, settings),
                )
            }
        }
    }

    fun moveGamePriority(gameName: String, offset: Int) {
        scope.launch {
            settingsRepository.moveGamePriority(gameName, offset)
            val settings = settingsRepository.settings.first()
            _snapshot.update {
                it.copy(campaigns = markSelected(it.campaigns, settings))
            }
        }
    }

    fun setGamePriority(gameName: String, priorityNumber: Int) {
        scope.launch {
            settingsRepository.setGamePriority(gameName, priorityNumber)
            val settings = settingsRepository.settings.first()
            _snapshot.update {
                it.copy(campaigns = markSelected(it.campaigns, settings))
            }
        }
    }

    fun clearGamePriority() {
        scope.launch {
            settingsRepository.clearGamePriority()
            val settings = settingsRepository.settings.first()
            _snapshot.update {
                it.copy(
                    campaigns = markSelected(it.campaigns, settings),
                    selectedCampaignIds = emptySet(),
                )
            }
        }
    }

    fun selectChannel(channelId: Long) {
        scope.launch {
            val selected = _snapshot.value.channels.firstOrNull { it.id == channelId }
                ?: return@launch
            updateSnapshot(RuntimePhase.Watching, "Selected ${selected.name}") {
                it.copy(
                    currentChannel = selected.copy(watching = true),
                    channels = it.channels.map { channel ->
                        channel.copy(watching = channel.id == channelId)
                    },
                )
            }
            appendActivity(RuntimePhase.Watching, "Manual channel selected", selected.name)
        }
    }

    fun resetSession() {
        scope.launch {
            miningJob?.cancelAndJoin()
            authJob?.cancelAndJoin()
            secureSessionStore.clear()
            settingsRepository.resetSessionSettings()
            dropsClaimedThisSession = 0
            dropClaimHandler.clearAttempts()
            _snapshot.value = RuntimeSnapshot(
                phase = RuntimePhase.Stopped,
                account = LoginSession(LoginState.LoggedOut, "Twitch login required"),
                currentTask = "Session reset",
                progressSummary = "Local session and selections cleared",
                lastUpdate = Instant.now(),
            )
            logRepository.append("INFO", "Twitch session reset")
        }
    }

    private suspend fun runMiningLoop() {
        val session = secureSessionStore.twitchSession()
        if (session == null) {
            updateSnapshot(RuntimePhase.Authenticating, "Twitch login required") {
                it.copy(
                    account = LoginSession(LoginState.LoginRequired, "Start Twitch device login"),
                    progressSummary = "Authenticate before the local miner can call Twitch.",
                    error = "Twitch login required.",
                )
            }
            appendActivity(RuntimePhase.Authenticating, "Login required before mining")
            return
        }

        runCatching {
            twitchApiClient.validateAccessToken(session.accessToken)
        }.onFailure { error ->
            updateSnapshot(RuntimePhase.Authenticating, "Stored Twitch session needs renewal") {
                it.copy(
                    account = LoginSession(LoginState.Expired, "Twitch session expired"),
                    error = error.message ?: "Twitch session expired",
                )
            }
            appendActivity(RuntimePhase.Authenticating, "Stored Twitch session expired")
            return
        }

        while (scope.isActive && miningJob?.isActive == true) {
            val settings = settingsRepository.settings.first()
            updateSnapshot(RuntimePhase.LoadingInventory, "Loading Twitch drops inventory")
            val campaigns = loadCampaigns(settings, session)
            var campaignSnapshot = campaigns
            campaignSnapshot = claimCompletedDrops(settings, session, campaignSnapshot)
            val selectedWork = selectCampaignWork(
                settings = settings,
                session = session,
                campaignSnapshot = campaignSnapshot,
            )
            if (selectedWork is CampaignWorkSelection.Idle) {
                updateSnapshot(RuntimePhase.Idle, selectedWork.task) {
                    it.copy(
                        campaigns = markSelected(campaignSnapshot, settings),
                        currentChannel = null,
                        activeCampaign = null,
                        activeDrop = null,
                        progressSummary = campaignSnapshot.progressSummary(),
                        error = null,
                    )
                }
                appendActivity(RuntimePhase.Idle, selectedWork.activityTitle, selectedWork.detail)
                delay(selectedWork.retryDelayMillis(settings))
                continue
            }

            val work = (selectedWork as CampaignWorkSelection.Selected).work
            var currentCampaign: Campaign = work.campaign
            val currentChannel = work.channel.copy(watching = true)
            val channels = work.channels
            val refreshAt = Instant.now().plus(Duration.ofMinutes(settings.inventoryRefreshMinutes.toLong()))
            while (
                scope.isActive &&
                miningJob?.isActive == true &&
                Instant.now().isBefore(refreshAt)
            ) {
                val activeDrop = currentCampaign.nextEarnableDrop()
                if (activeDrop == null) {
                    appendActivity(RuntimePhase.Idle, "Campaign completed", currentCampaign.name)
                    break
                }

                updateSnapshot(RuntimePhase.Watching, "Watching ${currentChannel.name}") {
                    it.copy(
                        campaigns = markSelected(campaignSnapshot, settings),
                        channels = channels.map { channel ->
                            channel.copy(watching = channel.id == currentChannel.id)
                        },
                        currentChannel = currentChannel,
                        activeCampaign = currentCampaign,
                        activeDrop = activeDrop,
                        progressSummary = listOf(currentCampaign).progressSummary(),
                        error = null,
                    )
                }

                val watchSucceeded = sendWatch(settings, session, currentCampaign, currentChannel)
                val progressedCampaign = updateProgress(
                    session = session,
                    campaign = currentCampaign,
                    channel = currentChannel,
                    fallbackBump = watchSucceeded || currentCampaign.isSampleCampaign,
                )
                currentCampaign = progressedCampaign
                campaignSnapshot = campaignSnapshot.replaceCampaign(currentCampaign)

                val claimable = firstClaimableDrop(session, currentCampaign)
                if (claimable != null) {
                    updateSnapshot(RuntimePhase.Claiming, "Claiming ${claimable.name}") {
                        it.copy(
                            campaigns = markSelected(campaignSnapshot, settings),
                            activeCampaign = currentCampaign,
                            activeDrop = claimable,
                        )
                    }
                    currentCampaign = claimDrop(settings, session, currentCampaign, claimable)
                    campaignSnapshot = campaignSnapshot.replaceCampaign(currentCampaign)
                }

                delay(settings.watchIntervalSeconds * 1000L)
            }
        }
    }

    private suspend fun claimCompletedDrops(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaigns: List<Campaign>,
    ): List<Campaign> {
        var updatedCampaigns = campaigns
        for (campaign in campaigns) {
            if (campaign.upcoming) {
                continue
            }
            var currentCampaign = updatedCampaigns.firstOrNull { it.id == campaign.id } ?: campaign
            for (drop in currentCampaign.drops) {
                if (drop.isClaimed || (!drop.hasCompletedProgress && !drop.canClaim)) {
                    continue
                }
                if (dropClaimHandler.suppressionFor(session, currentCampaign, drop) != null) {
                    continue
                }
                updateSnapshot(RuntimePhase.Claiming, "Claiming ${drop.name}") {
                    it.copy(
                        campaigns = markSelected(updatedCampaigns, settings),
                        activeCampaign = currentCampaign,
                        activeDrop = drop,
                        progressSummary = updatedCampaigns.progressSummary(),
                        error = null,
                    )
                }
                currentCampaign = claimDrop(settings, session, currentCampaign, drop)
                updatedCampaigns = updatedCampaigns.replaceCampaign(currentCampaign)
            }
        }
        return updatedCampaigns
    }

    private suspend fun selectCampaignWork(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaignSnapshot: List<Campaign>,
    ): CampaignWorkSelection {
        val decision = CampaignPrioritySelector.initialDecision(settings, campaignSnapshot)
        return selectFromCandidateDecision(settings, session, campaignSnapshot, decision)
    }

    private suspend fun selectFromCandidateDecision(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaignSnapshot: List<Campaign>,
        decision: CampaignCandidateDecision,
    ): CampaignWorkSelection =
        when (decision) {
            is CampaignCandidateDecision.Idle -> CampaignWorkSelection.Idle(decision)
            is CampaignCandidateDecision.Try -> {
                announceCandidateDecision(settings, campaignSnapshot, decision)
                val work = selectCampaignWithChannel(
                    settings = settings,
                    session = session,
                    campaignSnapshot = campaignSnapshot,
                    decision = decision,
                )
                if (work != null) {
                    CampaignWorkSelection.Selected(work)
                } else if (decision.mode == CampaignSelectionMode.Prioritized) {
                    selectFromCandidateDecision(
                        settings = settings,
                        session = session,
                        campaignSnapshot = campaignSnapshot,
                        decision = CampaignPrioritySelector.afterNoPrioritizedChannelDecision(
                            settings,
                            campaignSnapshot,
                        ),
                    )
                } else {
                    CampaignWorkSelection.Idle(CampaignPrioritySelector.noChannelDecision(decision.mode))
                }
            }
        }

    private suspend fun announceCandidateDecision(
        settings: AppSettings,
        campaignSnapshot: List<Campaign>,
        decision: CampaignCandidateDecision.Try,
    ) {
        if (!decision.mode.isAutoFallback) {
            return
        }
        updateSnapshot(RuntimePhase.SelectingCampaign, decision.task) {
            it.copy(
                campaigns = markSelected(campaignSnapshot, settings),
                currentChannel = null,
                activeCampaign = null,
                activeDrop = null,
                progressSummary = campaignSnapshot.progressSummary(),
                error = null,
            )
        }
        appendActivity(RuntimePhase.SelectingCampaign, "Falling back to Auto Mode", decision.detail)
    }

    private suspend fun selectCampaignWithChannel(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaignSnapshot: List<Campaign>,
        decision: CampaignCandidateDecision.Try,
    ): SelectedCampaignWork? {
        for (candidate in decision.candidates) {
            updateSnapshot(RuntimePhase.SelectingCampaign, decision.mode.selectionTask(candidate)) {
                it.copy(
                    campaigns = markSelected(campaignSnapshot, settings),
                    activeCampaign = candidate,
                    activeDrop = candidate.nextEarnableDrop(),
                    progressSummary = campaignSnapshot.progressSummary(),
                    selectedCampaignIds = settings.selectedCampaignIds,
                    error = null,
                )
            }

            updateSnapshot(RuntimePhase.FindingChannel, "Finding eligible live channels")
            val channels = loadChannels(settings, session, candidate)
            val selectedChannel = channels.firstOrNull { it.online && it.dropsEnabled }
            if (selectedChannel != null) {
                return SelectedCampaignWork(candidate, selectedChannel, channels)
            }

            updateSnapshot(RuntimePhase.Idle, "No eligible live channel for ${candidate.gameName}") {
                it.copy(
                    campaigns = markSelected(campaignSnapshot, settings),
                    channels = channels,
                    currentChannel = null,
                )
            }
            appendActivity(
                RuntimePhase.Idle,
                "No eligible live channel",
                decision.mode.noChannelDetail(candidate),
            )
        }
        return null
    }

    private suspend fun loadCampaigns(
        settings: AppSettings,
        session: StoredTwitchSession?,
    ): List<Campaign> {
        val loaded = if (session == null) {
            emptyList()
        } else {
            runCatching { twitchApiClient.fetchCampaigns(session) }
                .onFailure { appendActivity(RuntimePhase.Error, "Inventory fetch failed", it.message) }
                .getOrDefault(emptyList())
        }
        return when {
            loaded.isNotEmpty() -> loaded
            settings.useSampleDataFallback -> {
                appendActivity(RuntimePhase.LoadingInventory, "Using sample campaign fallback")
                SampleTwitchData.campaigns()
            }
            else -> loaded
        }
    }

    private suspend fun loadChannels(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaign: Campaign,
    ): List<Channel> {
        val loaded = runCatching {
            twitchApiClient.fetchEligibleChannels(session, campaign)
        }.onFailure {
            appendActivity(RuntimePhase.Error, "Channel discovery failed", it.message)
        }.getOrDefault(emptyList())
        return when {
            loaded.isNotEmpty() -> loaded
            settings.useSampleDataFallback && campaign.isSampleCampaign -> {
                appendActivity(RuntimePhase.FindingChannel, "Using sample channel fallback")
                SampleTwitchData.channels(campaign.gameName)
            }
            else -> loaded
        }
    }

    private suspend fun sendWatch(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaign: Campaign,
        channel: Channel,
    ): Boolean {
        if (campaign.isSampleCampaign) {
            return settings.useSampleDataFallback
        }
        if (channel.broadcastId == null) {
            return false
        }
        return runCatching {
            twitchApiClient.sendWatchMinute(session, channel)
        }.onFailure {
            appendActivity(RuntimePhase.Error, "Watch event failed", it.message)
        }.getOrDefault(false)
    }

    private suspend fun updateProgress(
        session: StoredTwitchSession,
        campaign: Campaign,
        channel: Channel,
        fallbackBump: Boolean,
    ): Campaign {
        val progress = runCatching {
            twitchApiClient.currentDrop(session, channel.id)
        }.getOrNull()
        if (progress != null) {
            return campaign.updateDrop(progress.dropId) { drop ->
                val current = progress.currentMinutes.coerceAtMost(drop.requiredMinutes)
                drop.copy(
                    currentMinutes = current,
                    progress = if (drop.requiredMinutes <= 0) 0f else current.toFloat() / drop.requiredMinutes,
                    canClaim = current >= drop.requiredMinutes && !drop.isClaimed,
                )
            }
        }
        if (!fallbackBump) {
            return campaign
        }
        val drop = campaign.nextEarnableDrop() ?: return campaign
        return campaign.updateDrop(drop.id) {
            val current = (it.currentMinutes + 1).coerceAtMost(it.requiredMinutes)
            it.copy(
                currentMinutes = current,
                progress = if (it.requiredMinutes <= 0) 0f else current.toFloat() / it.requiredMinutes,
                canClaim = current >= it.requiredMinutes && !it.isClaimed,
            )
        }
    }

    private suspend fun claimDrop(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaign: Campaign,
        drop: CampaignDrop,
    ): Campaign {
        val result = if (settings.useSampleDataFallback && campaign.isSampleCampaign) {
            RuntimeClaimResult(
                outcome = RuntimeClaimOutcome.Claimed,
                campaign = campaign,
                drop = drop,
                message = "Sample drop claim simulated.",
            )
        } else {
            dropClaimHandler.claim(session, campaign, drop)
        }
        val updatedCampaign = if (result.isTerminalSuccess) {
            if (result.shouldCountAsNewClaim) {
                dropsClaimedThisSession += 1
            }
            val title = when (result.outcome) {
                RuntimeClaimOutcome.AlreadyClaimed -> "Drop already claimed"
                else -> "Drop claimed"
            }
            val generatedNote = if (result.resolved?.generatedClaimId == true) {
                "generated drop instance ID"
            } else {
                result.twitchStatus
            }
            appendActivity(RuntimePhase.Claiming, title, listOfNotNull(drop.name, generatedNote).joinToString(" - "))
            campaign.updateDrop(drop.id) {
                it.copy(
                    currentMinutes = it.requiredMinutes,
                    progress = 1f,
                    isClaimed = true,
                    canClaim = false,
                )
            }
        } else {
            val detail = listOfNotNull(drop.name, result.message, result.retryAt?.let { "retry after $it" })
                .joinToString(" - ")
            appendActivity(RuntimePhase.Error, result.failureTitle(), detail)
            if (result.outcome == RuntimeClaimOutcome.InvalidToken) {
                updateSnapshot(RuntimePhase.Authenticating, "Stored Twitch session needs renewal") {
                    it.copy(
                        account = LoginSession(LoginState.Expired, "Twitch session expired"),
                        error = result.message ?: "Twitch session expired",
                    )
                }
                miningJob?.cancel()
            }
            campaign
        }
        val activeDrop = updatedCampaign.drops.firstOrNull { it.id == drop.id } ?: drop
        updateSnapshot(
            phase = if (result.outcome == RuntimeClaimOutcome.InvalidToken) {
                RuntimePhase.Authenticating
            } else {
                RuntimePhase.Claiming
            },
            task = result.statusTask(drop),
        ) {
            it.copy(
                activeCampaign = updatedCampaign,
                activeDrop = activeDrop,
                progressSummary = listOf(updatedCampaign).progressSummary(),
                error = if (result.isTerminalSuccess) null else result.message,
            )
        }
        return updatedCampaign
    }

    private fun firstClaimableDrop(
        session: StoredTwitchSession,
        campaign: Campaign,
    ): CampaignDrop? =
        campaign.drops.firstOrNull { drop ->
            !drop.isClaimed &&
                (drop.canClaim || drop.hasCompletedProgress) &&
                dropClaimHandler.suppressionFor(session, campaign, drop) == null
        }

    private fun markSelected(campaigns: List<Campaign>, settings: AppSettings): List<Campaign> =
        campaigns.map { campaign ->
            campaign.copy(selected = settings.isCampaignSelected(campaign))
        }

    private suspend fun updateSnapshot(
        phase: RuntimePhase,
        task: String,
        transform: (RuntimeSnapshot) -> RuntimeSnapshot = { it },
    ) {
        val now = Instant.now()
        _snapshot.update { current ->
            transform(current).copy(
                phase = phase,
                currentTask = task,
                lastUpdate = now,
                dropsClaimedThisSession = dropsClaimedThisSession,
            )
        }
    }

    private suspend fun appendActivity(
        phase: RuntimePhase,
        title: String,
        detail: String? = null,
    ) {
        val entry = RuntimeActivity(Instant.now(), phase, title, detail)
        _snapshot.update {
            it.copy(activity = (it.activity + entry).takeLast(120), lastUpdate = entry.timestamp)
        }
        logRepository.append(if (phase == RuntimePhase.Error) "ERROR" else "INFO", entry.toLine())
    }
}

private fun Campaign.nextEarnableDrop(): CampaignDrop? {
    val claimed = drops.filter { it.isClaimed }.map { it.id }.toSet()
    return drops.firstOrNull { drop ->
        !drop.isClaimed &&
            drop.currentMinutes < drop.requiredMinutes &&
            drop.preconditionDropIds.all { it in claimed }
    } ?: drops.firstOrNull { it.canClaim }
}

internal object CampaignPrioritySelector {
    fun select(settings: AppSettings, campaigns: List<Campaign>): Campaign? {
        return candidates(settings, campaigns).firstOrNull()
    }

    fun candidates(settings: AppSettings, campaigns: List<Campaign>): List<Campaign> {
        return when (val decision = initialDecision(settings, campaigns)) {
            is CampaignCandidateDecision.Try -> decision.candidates
            is CampaignCandidateDecision.Idle -> emptyList()
        }
    }

    fun initialDecision(settings: AppSettings, campaigns: List<Campaign>): CampaignCandidateDecision {
        if (!settings.hasGamePriority) {
            val autoCandidates = autoCandidates(campaigns)
            return if (autoCandidates.isEmpty()) {
                CampaignCandidateDecision.Idle(
                    task = "No available campaign can be mined",
                    detail = "Auto Mode found no linked active campaign with remaining or claimable drops.",
                )
            } else {
                CampaignCandidateDecision.Try(
                    mode = CampaignSelectionMode.Auto,
                    candidates = autoCandidates,
                    task = "Auto Mode selecting campaign",
                    detail = "No game priority is set.",
                )
            }
        }

        val priorityCandidates = prioritizedCandidates(settings, campaigns)
        if (priorityCandidates.isNotEmpty()) {
            return CampaignCandidateDecision.Try(
                mode = CampaignSelectionMode.Prioritized,
                candidates = priorityCandidates,
                task = "Selecting prioritized campaign",
                detail = "Trying prioritized games in order.",
            )
        }

        if (prioritizedGamesComplete(settings, campaigns)) {
            if (settings.fallbackToAutoWhenPrioritizedComplete) {
                val autoCandidates = autoFallbackCandidates(settings, campaigns)
                return if (autoCandidates.isEmpty()) {
                    CampaignCandidateDecision.Idle(
                        task = "All prioritized games are complete",
                        detail = "Auto Mode fallback is enabled, but no other eligible campaign is available.",
                        activityTitle = "Priority mining idle",
                    )
                } else {
                    CampaignCandidateDecision.Try(
                        mode = CampaignSelectionMode.AutoFallbackPrioritiesComplete,
                        candidates = autoCandidates,
                        task = "Prioritized games complete; using Auto Mode",
                        detail = "All prioritized games are complete.",
                    )
                }
            }

            return CampaignCandidateDecision.Idle(
                task = "All prioritized games are complete",
                detail = "Auto Mode fallback for completed prioritized games is disabled.",
                activityTitle = "Priority mining idle",
            )
        }

        return CampaignCandidateDecision.Idle(
            task = "No prioritized game can be mined",
            detail = "Prioritized games have no linked active campaign with remaining or claimable drops.",
        )
    }

    fun afterNoPrioritizedChannelDecision(
        settings: AppSettings,
        campaigns: List<Campaign>,
    ): CampaignCandidateDecision {
        if (!settings.hasGamePriority) {
            return noChannelDecision(CampaignSelectionMode.Auto)
        }
        if (!settings.fallbackToAutoWhenNoPrioritizedChannel) {
            return CampaignCandidateDecision.Idle(
                task = "No prioritized games have eligible live channels",
                detail = "Auto Mode fallback for prioritized games without live channels is disabled.",
                activityTitle = "Priority mining idle",
                retry = CampaignIdleRetry.WatchInterval,
            )
        }

        val autoCandidates = autoFallbackCandidates(settings, campaigns)
        return if (autoCandidates.isEmpty()) {
            CampaignCandidateDecision.Idle(
                task = "No prioritized games have eligible live channels",
                detail = "Auto Mode fallback is enabled, but no other eligible campaign is available.",
                activityTitle = "Priority mining idle",
                retry = CampaignIdleRetry.WatchInterval,
            )
        } else {
            CampaignCandidateDecision.Try(
                mode = CampaignSelectionMode.AutoFallbackNoPrioritizedChannel,
                candidates = autoCandidates,
                task = "No prioritized live channel; using Auto Mode",
                detail = "No prioritized games currently have eligible live channels.",
            )
        }
    }

    fun noChannelDecision(mode: CampaignSelectionMode): CampaignCandidateDecision.Idle =
        when (mode) {
            CampaignSelectionMode.Auto -> CampaignCandidateDecision.Idle(
                task = "No eligible live channel found",
                detail = "Auto Mode found eligible campaigns, but no eligible live channel is currently available.",
                activityTitle = "No eligible live channel",
                retry = CampaignIdleRetry.WatchInterval,
            )

            CampaignSelectionMode.Prioritized -> CampaignCandidateDecision.Idle(
                task = "No prioritized games have eligible live channels",
                detail = "Auto Mode fallback for prioritized games without live channels is disabled.",
                activityTitle = "Priority mining idle",
                retry = CampaignIdleRetry.WatchInterval,
            )

            CampaignSelectionMode.AutoFallbackPrioritiesComplete -> CampaignCandidateDecision.Idle(
                task = "Auto Mode fallback found no eligible live channel",
                detail = "Prioritized games are complete, and other eligible campaigns currently have no eligible live channel.",
                activityTitle = "Auto Mode fallback idle",
                retry = CampaignIdleRetry.WatchInterval,
            )

            CampaignSelectionMode.AutoFallbackNoPrioritizedChannel -> CampaignCandidateDecision.Idle(
                task = "Auto Mode fallback found no eligible live channel",
                detail = "Prioritized games and Auto Mode fallback campaigns currently have no eligible live channels.",
                activityTitle = "Auto Mode fallback idle",
                retry = CampaignIdleRetry.WatchInterval,
            )
        }

    fun prioritizedCandidates(settings: AppSettings, campaigns: List<Campaign>): List<Campaign> {
        val earnableCampaigns = campaigns.filter { it.canEarnLocally }
        return settings.selectedGamePriority.flatMap { gameName ->
            earnableCampaigns.filter { campaign ->
                campaign.gameName.equals(gameName, ignoreCase = true)
            }.sortedBy { it.remainingMinutes }
        }
    }

    fun autoFallbackCandidates(settings: AppSettings, campaigns: List<Campaign>): List<Campaign> =
        autoCandidates(campaigns).filter { campaign -> !settings.isGamePrioritized(campaign.gameName) }

    fun prioritizedGamesComplete(settings: AppSettings, campaigns: List<Campaign>): Boolean {
        if (!settings.hasGamePriority) {
            return false
        }
        return settings.selectedGamePriority.all { gameName ->
            val gameCampaigns = campaigns.filter { campaign ->
                campaign.gameName.equals(gameName, ignoreCase = true)
            }
            gameCampaigns.isNotEmpty() && gameCampaigns.all { it.isLocallyComplete }
        }
    }

    private fun autoCandidates(campaigns: List<Campaign>): List<Campaign> =
        campaigns.filter { it.canEarnLocally }
}

internal sealed class CampaignCandidateDecision {
    data class Try(
        val mode: CampaignSelectionMode,
        val candidates: List<Campaign>,
        val task: String,
        val detail: String,
    ) : CampaignCandidateDecision()

    data class Idle(
        val task: String,
        val detail: String,
        val activityTitle: String = "No available work",
        val retry: CampaignIdleRetry = CampaignIdleRetry.InventoryRefresh,
    ) : CampaignCandidateDecision()
}

internal enum class CampaignSelectionMode {
    Auto,
    Prioritized,
    AutoFallbackPrioritiesComplete,
    AutoFallbackNoPrioritizedChannel,
}

internal enum class CampaignIdleRetry {
    InventoryRefresh,
    WatchInterval,
}

private val CampaignSelectionMode.isAutoFallback: Boolean
    get() = this == CampaignSelectionMode.AutoFallbackPrioritiesComplete ||
        this == CampaignSelectionMode.AutoFallbackNoPrioritizedChannel

private fun CampaignSelectionMode.selectionTask(candidate: Campaign): String =
    when (this) {
        CampaignSelectionMode.Auto -> "Auto Mode selected ${candidate.gameName}"
        CampaignSelectionMode.Prioritized -> "Selected ${candidate.gameName}"
        CampaignSelectionMode.AutoFallbackPrioritiesComplete,
        CampaignSelectionMode.AutoFallbackNoPrioritizedChannel -> "Auto Mode selected ${candidate.gameName}"
    }

private fun CampaignSelectionMode.noChannelDetail(candidate: Campaign): String =
    when (this) {
        CampaignSelectionMode.Auto -> "${candidate.gameName}; trying next Auto Mode campaign."
        CampaignSelectionMode.Prioritized -> "${candidate.gameName}; trying next prioritized campaign."
        CampaignSelectionMode.AutoFallbackPrioritiesComplete,
        CampaignSelectionMode.AutoFallbackNoPrioritizedChannel ->
            "${candidate.gameName}; trying next Auto Mode fallback campaign."
    }

private fun CampaignCandidateDecision.Idle.retryDelayMillis(settings: AppSettings): Long =
    when (retry) {
        CampaignIdleRetry.InventoryRefresh -> settings.inventoryRefreshMinutes * 60_000L
        CampaignIdleRetry.WatchInterval -> settings.watchIntervalSeconds * 1000L
    }

private sealed class CampaignWorkSelection {
    data class Selected(val work: SelectedCampaignWork) : CampaignWorkSelection()

    data class Idle(private val decision: CampaignCandidateDecision.Idle) : CampaignWorkSelection() {
        val task: String
            get() = decision.task
        val detail: String
            get() = decision.detail
        val activityTitle: String
            get() = decision.activityTitle

        fun retryDelayMillis(settings: AppSettings): Long = decision.retryDelayMillis(settings)
    }
}

private data class SelectedCampaignWork(
    val campaign: Campaign,
    val channel: Channel,
    val channels: List<Channel>,
)

private val Campaign.isLocallyComplete: Boolean
    get() = drops.isNotEmpty() && drops.all { drop -> drop.isClaimed }

private val Campaign.isSampleCampaign: Boolean
    get() = id.startsWith("sample-")

private fun Campaign.updateDrop(
    dropId: String,
    transform: (CampaignDrop) -> CampaignDrop,
): Campaign {
    val updatedDrops = drops.map { drop -> if (drop.id == dropId) transform(drop) else drop }
    return copy(
        drops = updatedDrops,
        claimedDrops = updatedDrops.count { it.isClaimed },
        totalDrops = updatedDrops.size,
    )
}

private fun List<Campaign>.replaceCampaign(campaign: Campaign): List<Campaign> =
    map { existing -> if (existing.id == campaign.id) campaign else existing }

private fun List<Campaign>.progressSummary(): String {
    if (isEmpty()) {
        return "No campaign data"
    }
    val active = count { it.active }
    val claimed = sumOf { it.claimedDrops }
    val total = sumOf { it.totalDrops }.coerceAtLeast(1)
    val percent = ((claimed.toFloat() / total) * 100).toInt()
    return "$active active campaigns, $claimed/$total drops claimed ($percent%)"
}

private fun RuntimeClaimResult.failureTitle(): String =
    when (outcome) {
        RuntimeClaimOutcome.MissingIdentifier -> "Drop claim missing required ID"
        RuntimeClaimOutcome.NotClaimable -> "No claimable drops"
        RuntimeClaimOutcome.InvalidToken -> "Drop claim needs login renewal"
        RuntimeClaimOutcome.NetworkFailure -> "Drop claim network failure"
        RuntimeClaimOutcome.UnexpectedResponse -> "Drop claim response unexpected"
        RuntimeClaimOutcome.Suppressed -> "Drop claim retry delayed"
        RuntimeClaimOutcome.Failed -> "Drop claim failed"
        RuntimeClaimOutcome.Claimed,
        RuntimeClaimOutcome.AlreadyClaimed -> "Drop claimed"
    }

private fun RuntimeClaimResult.statusTask(drop: CampaignDrop): String =
    when (outcome) {
        RuntimeClaimOutcome.Claimed -> "Claimed ${drop.name}"
        RuntimeClaimOutcome.AlreadyClaimed -> "${drop.name} was already claimed"
        RuntimeClaimOutcome.Suppressed -> "Claim retry delayed for ${drop.name}"
        RuntimeClaimOutcome.MissingIdentifier -> "Cannot claim ${drop.name}"
        RuntimeClaimOutcome.NotClaimable -> "No claimable drops"
        RuntimeClaimOutcome.InvalidToken -> "Twitch session needs renewal"
        RuntimeClaimOutcome.NetworkFailure -> "Claim network failure for ${drop.name}"
        RuntimeClaimOutcome.UnexpectedResponse -> "Unexpected claim response for ${drop.name}"
        RuntimeClaimOutcome.Failed -> "Claim failed for ${drop.name}"
    }
