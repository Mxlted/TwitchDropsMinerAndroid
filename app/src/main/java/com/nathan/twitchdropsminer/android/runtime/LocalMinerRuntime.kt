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
import com.nathan.twitchdropsminer.android.data.network.NetworkStatusProvider
import com.nathan.twitchdropsminer.android.data.twitch.CurrentDropProgress
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApi
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiErrorType
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val UnlinkedProgressCheckIntervals = 3L
private const val RejectedWatchFailureThreshold = 3
private val MinUnlinkedProgressCheckDelay: Duration = Duration.ofMinutes(2)
private val MaxUnlinkedProgressCheckDelay: Duration = Duration.ofMinutes(5)
private val UnlinkedNoProgressRetryDelay: Duration = Duration.ofMinutes(30)
private val FailedChannelRetryDelay: Duration = Duration.ofMinutes(15)

class LocalMinerRuntime(
    private val settingsRepository: SettingsRepository,
    private val secureSessionStore: SecureSessionStore,
    private val logRepository: LogRepository,
    private val twitchApiClient: TwitchApi,
    private val networkStatusProvider: NetworkStatusProvider,
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
    private val unlinkedNoProgressSkips = mutableMapOf<String, Instant>()
    private val failedChannelSkips = mutableMapOf<Long, Instant>()
    private val channelControlRequests = MutableStateFlow(ChannelControlRequest())
    private var lastLoggedExcludedCampaignIds: Set<String> = emptySet()
    private var waitingForNetwork = false

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
                    awaitUsableNetwork()
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

                    while (currentCoroutineContext().isActive && Instant.now().isBefore(authorization.expiresAt)) {
                        delay(authorization.intervalSeconds * 1000L)
                        awaitUsableNetwork()
                        if (!Instant.now().isBefore(authorization.expiresAt)) {
                            break
                        }
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
                } catch (error: CancellationException) {
                    throw error
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
        unlinkedNoProgressSkips.clear()
        failedChannelSkips.clear()
        lastLoggedExcludedCampaignIds = emptySet()
        waitingForNetwork = false
        _snapshot.update { it.copy(miningActive = true, error = null) }
        miningJob = scope.launch {
            appendActivity(RuntimePhase.LoadingInventory, "Local miner started")
            try {
                runMiningLoop()
            } catch (error: CancellationException) {
                throw error
            } catch (error: TwitchApiException) {
                if (error.type == TwitchApiErrorType.InvalidToken) {
                    expireTwitchSession(error.message)
                } else {
                    reportUnexpectedMinerFailure(error)
                }
            } catch (error: Throwable) {
                reportUnexpectedMinerFailure(error)
            } finally {
                _snapshot.update { it.copy(miningActive = false) }
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
            it.copy(
                currentChannel = null,
                activeCampaign = null,
                activeDrop = null,
                miningActive = false,
                channelSearchInProgress = false,
            )
        }
        appendActivity(RuntimePhase.Stopped, "Local miner stopped")
    }

    suspend fun refreshInventoryOnce() {
        val settings = settingsRepository.settings.first()
        val session = secureSessionStore.twitchSession()
        if (session == null) {
            updateSnapshot(RuntimePhase.Authenticating, "Twitch login required") {
                it.copy(
                    account = LoginSession(LoginState.LoginRequired, "Start Twitch device login"),
                    error = "Twitch login required.",
                )
            }
            return
        }
        awaitUsableNetwork()
        val campaignLoad = loadCampaigns(
            settings = settings,
            session = session,
            previousCampaigns = _snapshot.value.campaigns,
        )
        if (campaignLoad.failure != null) {
            updateSnapshot(RuntimePhase.Error, "Inventory refresh failed") {
                it.copy(
                    campaigns = markSelected(campaignLoad.campaigns, campaignLoad.settings),
                    progressSummary = campaignLoad.campaigns.progressSummary(),
                    error = campaignLoad.failure,
                )
            }
            return
        }
        val campaigns = campaignLoad.campaigns
        val effectiveSettings = campaignLoad.settings
        updateSnapshot(RuntimePhase.Idle, "Inventory refreshed") {
            it.copy(
                campaigns = markSelected(campaigns, effectiveSettings),
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
        val current = _snapshot.value
        if (
            !current.isRunning ||
            current.watchingChannel?.id == channelId ||
            current.channels.none { channel -> channel.id == channelId }
        ) {
            return
        }
        _snapshot.update {
            it.copy(
                phase = RuntimePhase.FindingChannel,
                currentTask = "Switching to selected channel",
                channelSearchInProgress = false,
                lastUpdate = Instant.now(),
                error = null,
            )
        }
        channelControlRequests.update { request ->
            ChannelControlRequest(
                id = request.id + 1L,
                selectedChannelId = channelId,
            )
        }
        scope.launch {
            appendActivity(RuntimePhase.FindingChannel, "Channel selection requested")
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
            unlinkedNoProgressSkips.clear()
            failedChannelSkips.clear()
            waitingForNetwork = false
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

        var validationFailures = 0
        while (currentCoroutineContext().isActive) {
            awaitUsableNetwork()
            val validation = runCatchingCancellable {
                twitchApiClient.validateAccessToken(session.accessToken)
            }
            if (validation.isSuccess) {
                break
            }
            val error = validation.exceptionOrNull() ?: continue
            if (error is TwitchApiException && error.type == TwitchApiErrorType.InvalidToken) {
                expireTwitchSession(error.message)
                return
            }
            validationFailures += 1
            val retryDelay = RuntimeRetryBackoff.delayFor(validationFailures)
            updateSnapshot(RuntimePhase.Error, "Unable to validate Twitch session") {
                it.copy(
                    error = "${error.message ?: "Twitch validation failed"} Retrying in ${retryDelay.runtimeLabel()}.",
                )
            }
            delay(retryDelay.toMillis())
        }

        var inventoryFailures = 0
        var channelDiscoveryFailures = 0
        var handledChannelControlRequestId = channelControlRequests.value.id
        while (currentCoroutineContext().isActive) {
            awaitUsableNetwork()
            var settings = settingsRepository.settings.first()
            updateSnapshot(RuntimePhase.LoadingInventory, "Loading Twitch drops inventory")
            val campaignLoad = loadCampaigns(
                settings = settings,
                session = session,
                previousCampaigns = _snapshot.value.campaigns,
            )
            settings = campaignLoad.settings
            if (campaignLoad.failure != null) {
                inventoryFailures += 1
                val retryDelay = RuntimeRetryBackoff.delayFor(inventoryFailures)
                updateSnapshot(RuntimePhase.Error, "Inventory unavailable; retrying") {
                    it.copy(
                        campaigns = markSelected(campaignLoad.campaigns, settings),
                        channels = emptyList(),
                        currentChannel = null,
                        activeCampaign = null,
                        activeDrop = null,
                        progressSummary = campaignLoad.campaigns.progressSummary(),
                        error = "${campaignLoad.failure} Retrying in ${retryDelay.runtimeLabel()}.",
                    )
                }
                delay(retryDelay.toMillis())
                continue
            }
            inventoryFailures = 0
            val campaigns = campaignLoad.campaigns
            var campaignSnapshot = campaigns
            campaignSnapshot = claimCompletedDrops(settings, session, campaignSnapshot)
            val selectedWork = try {
                selectCampaignWork(
                    settings = settings,
                    session = session,
                    campaignSnapshot = campaignSnapshot,
                )
            } catch (error: ChannelDiscoveryUnavailableException) {
                channelDiscoveryFailures += 1
                val retryDelay = RuntimeRetryBackoff.delayFor(channelDiscoveryFailures)
                updateSnapshot(RuntimePhase.Error, "Channel discovery unavailable; retrying") {
                    it.copy(
                        campaigns = markSelected(campaignSnapshot, settings),
                        channels = emptyList(),
                        currentChannel = null,
                        activeCampaign = null,
                        activeDrop = null,
                        progressSummary = campaignSnapshot.progressSummary(),
                        error = "${error.message} Retrying in ${retryDelay.runtimeLabel()}.",
                    )
                }
                delay(retryDelay.toMillis())
                continue
            }
            channelDiscoveryFailures = 0
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
            var currentChannel = work.channel.copy(watching = true)
            var channels = work.channels
            val refreshAt = Instant.now().plus(Duration.ofMinutes(settings.inventoryRefreshMinutes.toLong()))
            var unlinkedProgressProbe = currentCampaign.startUnlinkedProgressProbe(settings)
            if (unlinkedProgressProbe != null) {
                appendActivity(
                    RuntimePhase.Watching,
                    "Watching unlinked game",
                    "${currentCampaign.gameName} on ${currentChannel.name}",
                )
                appendActivity(
                    RuntimePhase.Watching,
                    "Checking unlinked drop progress",
                    "Will verify real Twitch progress in about ${unlinkedProgressProbe.checkWindowLabel}.",
                )
            }
            var consecutiveRejectedWatchEvents = 0
            var transientWatchFailures = 0
            while (
                currentCoroutineContext().isActive &&
                Instant.now().isBefore(refreshAt)
            ) {
                val resumedAfterNetworkLoss = awaitUsableNetwork()
                if (!Instant.now().isBefore(refreshAt)) {
                    break
                }
                if (resumedAfterNetworkLoss && unlinkedProgressProbe != null) {
                    unlinkedProgressProbe = currentCampaign.startUnlinkedProgressProbe(settings)
                    appendActivity(
                        RuntimePhase.Watching,
                        "Restarting unlinked progress check",
                        "The network interruption is excluded from the progress-check window.",
                    )
                }
                val pendingChannelControlRequest = channelControlRequests.value
                if (pendingChannelControlRequest.id != handledChannelControlRequestId) {
                    handledChannelControlRequestId = pendingChannelControlRequest.id
                    val requestedChannelId = pendingChannelControlRequest.selectedChannelId
                    if (requestedChannelId == null) {
                        val search = findCompatibleChannels(
                            session = session,
                            campaign = currentCampaign,
                            originalChannel = currentChannel,
                        )
                        channels = search.channels
                        updateSnapshot(RuntimePhase.Watching, search.task) {
                            it.copy(
                                channels = channels.markWatching(currentChannel.id),
                                currentChannel = currentChannel.copy(watching = true),
                                activeCampaign = currentCampaign,
                                activeDrop = currentCampaign.nextEarnableDrop(),
                                channelSearchInProgress = false,
                                error = null,
                            )
                        }
                        appendActivity(
                            RuntimePhase.Watching,
                            "Compatible channel list updated",
                            search.detail,
                        )
                    } else {
                        val selected = ChannelPickerSelection.findCompatibleChannel(
                            channels = channels,
                            channelId = requestedChannelId,
                        )
                        if (selected == null || selected.id == currentChannel.id) {
                            updateSnapshot(
                                RuntimePhase.Watching,
                                "Selected channel unavailable; keeping ${currentChannel.name}",
                            ) {
                                it.copy(
                                    channels = channels.markWatching(currentChannel.id),
                                    currentChannel = currentChannel.copy(watching = true),
                                    channelSearchInProgress = false,
                                    error = null,
                                )
                            }
                            appendActivity(
                                RuntimePhase.Watching,
                                "Channel selection ignored",
                                "The selected streamer is no longer compatible or live; ${currentChannel.name} remains active.",
                            )
                        } else {
                            val previousChannel = currentChannel
                            currentChannel = selected.copy(watching = true)
                            consecutiveRejectedWatchEvents = 0
                            transientWatchFailures = 0
                            unlinkedProgressProbe = currentCampaign.startUnlinkedProgressProbe(settings)
                            updateSnapshot(
                                RuntimePhase.Watching,
                                "Switched to ${currentChannel.name}",
                            ) {
                                it.copy(
                                    channels = channels.markWatching(currentChannel.id),
                                    currentChannel = currentChannel,
                                    activeCampaign = currentCampaign,
                                    activeDrop = currentCampaign.nextEarnableDrop(),
                                    channelSearchInProgress = false,
                                    error = null,
                                )
                            }
                            appendActivity(
                                RuntimePhase.Watching,
                                "Switched to selected channel",
                                "${previousChannel.name} → ${currentChannel.name} for ${currentCampaign.gameName}.",
                            )
                        }
                    }
                }
                val latestSettings = settingsRepository.settings.first()
                if (ActiveWatchGuard.shouldStopForExcludedCampaign(latestSettings, currentCampaign)) {
                    settings = latestSettings
                    updateSnapshot(RuntimePhase.Idle, "Campaign excluded; stopping current watch") {
                        it.copy(
                            campaigns = markSelected(campaignSnapshot, settings),
                            channels = channels.map { channel -> channel.copy(watching = false) },
                            currentChannel = null,
                            activeCampaign = null,
                            activeDrop = null,
                            progressSummary = campaignSnapshot.progressSummary(),
                            error = null,
                        )
                    }
                    appendActivity(
                        RuntimePhase.Idle,
                        "Campaign excluded while active",
                        "${currentCampaign.gameName}; stopping current watch and reselecting.",
                    )
                    break
                }

                val activeDrop = currentCampaign.nextEarnableDrop()
                if (activeDrop == null) {
                    appendActivity(RuntimePhase.Idle, "Campaign completed", currentCampaign.name)
                    break
                }

                updateSnapshot(
                    RuntimePhase.Watching,
                    currentCampaign.watchingTask(currentChannel, unlinkedProgressProbe),
                ) {
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

                val watchAttempt = sendWatch(session, currentChannel)
                when (watchAttempt) {
                    WatchAttemptResult.Accepted -> {
                        consecutiveRejectedWatchEvents = 0
                        transientWatchFailures = 0
                    }

                    WatchAttemptResult.Rejected -> {
                        transientWatchFailures = 0
                        consecutiveRejectedWatchEvents += 1
                        if (consecutiveRejectedWatchEvents >= RejectedWatchFailureThreshold) {
                            failedChannelSkips[currentChannel.id] = Instant.now()
                            updateSnapshot(
                                RuntimePhase.Idle,
                                "Switching away from an unhealthy channel",
                            ) {
                                it.copy(
                                    channels = channels.map { channel -> channel.copy(watching = false) },
                                    currentChannel = null,
                                    activeCampaign = currentCampaign,
                                    activeDrop = activeDrop,
                                    error = null,
                                )
                            }
                            appendActivity(
                                RuntimePhase.Idle,
                                "Channel watch events repeatedly rejected",
                                "${currentChannel.name} rejected $consecutiveRejectedWatchEvents consecutive watch events; trying another channel.",
                            )
                            break
                        }
                    }

                    is WatchAttemptResult.Failed -> {
                        transientWatchFailures += 1
                        consecutiveRejectedWatchEvents = 0
                        val retryDelay = RuntimeRetryBackoff.delayFor(transientWatchFailures)
                        updateSnapshot(RuntimePhase.Error, "Watch request failed; retrying") {
                            it.copy(
                                error = "${watchAttempt.message} Retrying in ${retryDelay.runtimeLabel()}.",
                            )
                        }
                        delay(retryDelay.toMillis())
                        continue
                    }
                }
                val progressedCampaign = updateProgress(
                    session = session,
                    campaign = currentCampaign,
                    channel = currentChannel,
                )
                currentCampaign = progressedCampaign
                campaignSnapshot = campaignSnapshot.replaceCampaign(currentCampaign)

                val probe = unlinkedProgressProbe
                var shouldMoveToNextUnlinked = false
                if (probe != null) {
                    when (val result = probe.observe(currentCampaign, Instant.now())) {
                        is UnlinkedProgressProbeResult.Continue -> {
                            unlinkedProgressProbe = result.probe
                            if (result.progressDetectedNow) {
                                appendActivity(
                                    RuntimePhase.Watching,
                                    "Unlinked progress detected",
                                    "${currentCampaign.gameName} progress increased; continuing to watch.",
                                )
                            }
                        }

                        is UnlinkedProgressProbeResult.Skip -> {
                            unlinkedNoProgressSkips[currentCampaign.id] = result.checkedAt
                            updateSnapshot(
                                RuntimePhase.Idle,
                                "Skipping unlinked game with no progress",
                            ) {
                                it.copy(
                                    campaigns = markSelected(campaignSnapshot, settings),
                                    channels = channels.map { channel -> channel.copy(watching = false) },
                                    currentChannel = null,
                                    activeCampaign = currentCampaign,
                                    activeDrop = currentCampaign.nextEarnableDrop(),
                                    progressSummary = listOf(currentCampaign).progressSummary(),
                                    error = null,
                                )
                            }
                            appendActivity(
                                RuntimePhase.Idle,
                                "Skipped unlinked game with no progress",
                                "${currentCampaign.gameName}; no progress after ${result.elapsedLabel}, trying next unlinked game.",
                            )
                            shouldMoveToNextUnlinked = true
                        }
                    }
                }
                if (shouldMoveToNextUnlinked) {
                    break
                }

                val claimable = firstClaimableDrop(session, currentCampaign)
                if (claimable != null) {
                    updateSnapshot(RuntimePhase.Claiming, "Claiming ${claimable.name}") {
                        it.copy(
                            campaigns = markSelected(campaignSnapshot, settings),
                            activeCampaign = currentCampaign,
                            activeDrop = claimable,
                        )
                    }
                    currentCampaign = claimDrop(session, currentCampaign, claimable)
                    campaignSnapshot = campaignSnapshot.replaceCampaign(currentCampaign)
                }

                withTimeoutOrNull(settings.watchIntervalSeconds * 1000L) {
                    channelControlRequests.first { request ->
                        request.id != handledChannelControlRequestId
                    }
                }
            }
        }
    }

    private suspend fun findCompatibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        originalChannel: Channel,
    ): CompatibleChannelSearch {
        updateSnapshot(RuntimePhase.FindingChannel, "Checking compatible live channels") {
            it.copy(error = null)
        }
        val discovered = try {
            loadChannels(session, campaign)
        } catch (error: ChannelDiscoveryUnavailableException) {
            return CompatibleChannelSearch(
                channels = listOf(originalChannel),
                task = "Channel search failed; keeping ${originalChannel.name}",
                detail = "Channel search failed, so ${originalChannel.name} remains active: ${error.message}",
            )
        }
        val alternatives = EligibleChannelSelector.candidates(
            channels = discovered,
            skippedChannelIds = failedChannelSkips.keys + originalChannel.id,
        )
        val availableChannels = listOf(originalChannel) + alternatives
        return CompatibleChannelSearch(
            channels = availableChannels,
            task = if (alternatives.isEmpty()) {
                "No alternate channels found; keeping ${originalChannel.name}"
            } else {
                "Choose from ${alternatives.size} compatible alternate channels"
            },
            detail = "Found ${alternatives.size} alternate streamer${if (alternatives.size == 1) "" else "s"} for ${campaign.gameName}; ${originalChannel.name} remains active until a selection is made.",
        )
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
                currentCampaign = claimDrop(session, currentCampaign, drop)
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
        pruneExpiredUnlinkedSkips(Instant.now())
        val unskippedCampaigns = campaignSnapshot.withoutSkippedUnlinkedCampaigns(
            unlinkedNoProgressSkips.keys,
        )
        val excludedCampaigns = unskippedCampaigns.filter { settings.isCampaignExcluded(it) }
        val excludedCampaignIds = excludedCampaigns.normalizedCampaignIds()
        if (excludedCampaignIds.isNotEmpty() && excludedCampaignIds != lastLoggedExcludedCampaignIds) {
            appendExcludedCampaignSkips(excludedCampaigns)
            lastLoggedExcludedCampaignIds = excludedCampaignIds
        } else if (excludedCampaignIds.isEmpty()) {
            lastLoggedExcludedCampaignIds = emptySet()
        }
        val decision = CampaignPrioritySelector.initialDecision(settings, unskippedCampaigns)
        return selectFromCandidateDecision(
            settings = settings,
            session = session,
            campaignSnapshot = campaignSnapshot,
            selectionCampaigns = unskippedCampaigns,
            decision = decision,
        )
    }

    private fun pruneExpiredUnlinkedSkips(now: Instant) {
        val expiredCampaignIds = unlinkedNoProgressSkips
            .filterValues { skippedAt ->
                Duration.between(skippedAt, now) >= UnlinkedNoProgressRetryDelay
            }
            .keys
        expiredCampaignIds.forEach(unlinkedNoProgressSkips::remove)
    }

    private fun pruneExpiredChannelSkips(now: Instant) {
        val expiredChannelIds = failedChannelSkips
            .filterValues { skippedAt ->
                Duration.between(skippedAt, now) >= FailedChannelRetryDelay
            }
            .keys
        expiredChannelIds.forEach(failedChannelSkips::remove)
    }

    private suspend fun appendExcludedCampaignSkips(campaigns: List<Campaign>) {
        val skipped = campaigns.distinctBy { it.id }
        val title = if (skipped.size == 1) {
            "Skipped excluded campaign"
        } else {
            "Skipped excluded campaigns"
        }
        appendActivity(
            RuntimePhase.SelectingCampaign,
            title,
            skipped.selectionLabel(limit = 4),
        )
    }

    private suspend fun selectFromCandidateDecision(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaignSnapshot: List<Campaign>,
        selectionCampaigns: List<Campaign>,
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
                } else {
                    selectFromCandidateDecision(
                        settings = settings,
                        session = session,
                        campaignSnapshot = campaignSnapshot,
                        selectionCampaigns = selectionCampaigns,
                        decision = CampaignPrioritySelector.afterNoChannelDecision(
                            settings,
                            selectionCampaigns,
                            decision.mode,
                        ),
                    )
                }
            }
        }

    private suspend fun announceCandidateDecision(
        settings: AppSettings,
        campaignSnapshot: List<Campaign>,
        decision: CampaignCandidateDecision.Try,
    ) {
        val activityTitle = when {
            decision.mode.isAutoFallback -> "Falling back to Auto Mode"
            decision.mode.isUnlinked -> "Trying unlinked games"
            else -> return
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
        appendActivity(RuntimePhase.SelectingCampaign, activityTitle, decision.detail)
    }

    private suspend fun selectCampaignWithChannel(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaignSnapshot: List<Campaign>,
        decision: CampaignCandidateDecision.Try,
    ): SelectedCampaignWork? {
        pruneExpiredChannelSkips(Instant.now())
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
            val channels = loadChannels(session, candidate)
            val selectedChannel = EligibleChannelSelector.select(
                channels = channels,
                skippedChannelIds = failedChannelSkips.keys,
            )
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
        session: StoredTwitchSession,
        previousCampaigns: List<Campaign>,
    ): CampaignLoadResult {
        val loaded = try {
            twitchApiClient.fetchCampaigns(session)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error.throwIfInvalidToken()
            val message = error.message ?: "Unable to load Twitch inventory."
            appendActivity(RuntimePhase.Error, "Inventory fetch failed", message)
            return CampaignLoadResult(
                campaigns = previousCampaigns,
                settings = settings,
                failure = message,
            )
        }
        val effectiveSettings = if (loaded.isEmpty()) {
            settings
        } else {
            cleanPrioritizedGamesWithoutCampaigns(settings, loaded)
        }
        return CampaignLoadResult(loaded, effectiveSettings)
    }

    private suspend fun cleanPrioritizedGamesWithoutCampaigns(
        settings: AppSettings,
        campaigns: List<Campaign>,
    ): AppSettings {
        if (!settings.hasGamePriority) {
            return settings
        }
        val removedGames = settingsRepository.removeGamePrioritiesWithoutAvailableCampaigns(
            campaigns.availablePriorityGameNames(),
        )
        if (removedGames.isEmpty()) {
            return settings
        }
        val title = if (removedGames.size == 1) {
            "Removed unavailable prioritized game"
        } else {
            "Removed unavailable prioritized games"
        }
        appendActivity(RuntimePhase.LoadingInventory, title, removedGames.joinToString())
        return settingsRepository.settings.first()
    }

    private suspend fun loadChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
    ): List<Channel> {
        return try {
            twitchApiClient.fetchEligibleChannels(session, campaign)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error.throwIfInvalidToken()
            val message = error.message ?: "Unable to discover Twitch channels."
            appendActivity(RuntimePhase.Error, "Channel discovery failed", message)
            throw ChannelDiscoveryUnavailableException(message, error)
        }
    }

    fun findNewChannel() {
        val current = _snapshot.value
        if (
            !current.isRunning ||
            current.phase != RuntimePhase.Watching ||
            current.activeCampaign == null ||
            current.watchingChannel == null
        ) {
            return
        }
        _snapshot.update {
            it.copy(
                phase = RuntimePhase.FindingChannel,
                currentTask = "Loading compatible channels",
                channelSearchInProgress = true,
                lastUpdate = Instant.now(),
                error = null,
            )
        }
        channelControlRequests.update { request ->
            ChannelControlRequest(id = request.id + 1L)
        }
        scope.launch {
            appendActivity(
                RuntimePhase.FindingChannel,
                "Compatible channel list requested",
                "Searching for streamers compatible with ${current.activeCampaign.gameName}.",
            )
        }
    }

    private suspend fun sendWatch(
        session: StoredTwitchSession,
        channel: Channel,
    ): WatchAttemptResult {
        if (channel.broadcastId == null) {
            return WatchAttemptResult.Rejected
        }
        return try {
            if (twitchApiClient.sendWatchMinute(session, channel)) {
                WatchAttemptResult.Accepted
            } else {
                WatchAttemptResult.Rejected
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error.throwIfInvalidToken()
            val message = error.message ?: "Twitch watch request failed."
            appendActivity(RuntimePhase.Error, "Watch event failed", message)
            WatchAttemptResult.Failed(message)
        }
    }

    private suspend fun updateProgress(
        session: StoredTwitchSession,
        campaign: Campaign,
        channel: Channel,
    ): Campaign {
        val progress = runCatchingCancellable {
            twitchApiClient.currentDrop(session, channel.id)
        }.onFailure {
            it.throwIfInvalidToken()
        }.getOrNull()
        if (progress != null) {
            when (val applied = campaign.applyTwitchProgress(progress)) {
                is TwitchProgressUpdate.Updated -> return applied.campaign
                is TwitchProgressUpdate.UnexpectedDrop -> {
                    appendActivity(
                        RuntimePhase.Watching,
                        "Ignoring progress for unexpected drop",
                        "Twitch reported drop ${progress.dropId} (${progress.currentMinutes}m) on " +
                            "${channel.name}, which is not part of ${campaign.gameName} (${campaign.id}).",
                    )
                    return campaign
                }
            }
        }
        return campaign
    }

    private suspend fun claimDrop(
        session: StoredTwitchSession,
        campaign: Campaign,
        drop: CampaignDrop,
    ): Campaign {
        val result = dropClaimHandler.claim(session, campaign, drop)
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
    ): CampaignDrop? {
        return campaign.drops.firstOrNull { drop ->
            !drop.isClaimed &&
                (drop.canClaim || drop.hasCompletedProgress) &&
                dropClaimHandler.suppressionFor(session, campaign, drop) == null
        }
    }

    private fun markSelected(campaigns: List<Campaign>, settings: AppSettings): List<Campaign> =
        campaigns.map { campaign ->
            campaign.copy(selected = settings.isCampaignSelected(campaign))
        }

    private suspend fun awaitUsableNetwork(): Boolean {
        if (networkStatusProvider.isOnline.value) {
            waitingForNetwork = false
            return false
        }
        if (!waitingForNetwork) {
            waitingForNetwork = true
            updateSnapshot(RuntimePhase.Idle, "Waiting for internet connection") {
                it.copy(
                    channels = it.channels.map { channel -> channel.copy(watching = false) },
                    currentChannel = null,
                    error = null,
                )
            }
            appendActivity(
                RuntimePhase.Idle,
                "Internet connection unavailable",
                "Network work is paused until Android reports a validated connection.",
            )
        }
        networkStatusProvider.awaitOnline()
        waitingForNetwork = false
        appendActivity(RuntimePhase.Connecting, "Internet connection restored")
        return true
    }

    private suspend fun expireTwitchSession(message: String?) {
        updateSnapshot(RuntimePhase.Authenticating, "Stored Twitch session needs renewal") {
            it.copy(
                account = LoginSession(LoginState.Expired, "Twitch session expired"),
                channels = it.channels.map { channel -> channel.copy(watching = false) },
                currentChannel = null,
                channelSearchInProgress = false,
                error = message ?: "Twitch session expired",
            )
        }
        appendActivity(RuntimePhase.Authenticating, "Stored Twitch session expired")
    }

    private suspend fun reportUnexpectedMinerFailure(error: Throwable) {
        updateSnapshot(RuntimePhase.Error, "Local miner stopped after an unexpected error") {
            it.copy(
                channels = it.channels.map { channel -> channel.copy(watching = false) },
                currentChannel = null,
                channelSearchInProgress = false,
                error = error.message ?: "Unexpected local miner failure.",
            )
        }
        appendActivity(
            RuntimePhase.Error,
            "Local miner stopped after unexpected error",
            error.message,
        )
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

private fun Campaign.startUnlinkedProgressProbe(settings: AppSettings): UnlinkedProgressProbe? =
    if (canTryUnlinkedLocally) {
        UnlinkedProgressProbe.start(this, settings, Instant.now())
    } else {
        null
    }

private fun Campaign.watchingTask(
    channel: Channel,
    unlinkedProgressProbe: UnlinkedProgressProbe?,
): String =
    when {
        unlinkedProgressProbe != null && !unlinkedProgressProbe.progressDetected ->
            "Checking unlinked progress on ${channel.name}"

        canTryUnlinkedLocally -> "Watching unlinked game on ${channel.name}"
        else -> "Watching ${channel.name}"
    }

internal sealed class TwitchProgressUpdate {
    data class Updated(val campaign: Campaign) : TwitchProgressUpdate()
    object UnexpectedDrop : TwitchProgressUpdate()
}

/**
 * Applies Twitch-reported progress to the matching drop. If Twitch reports progress for a drop
 * that is not part of this campaign, this is a safe no-op signalled by [TwitchProgressUpdate.UnexpectedDrop]
 * so the caller can log enough detail to diagnose the mismatch.
 */
internal fun Campaign.applyTwitchProgress(progress: CurrentDropProgress): TwitchProgressUpdate {
    if (drops.none { it.id == progress.dropId }) {
        return TwitchProgressUpdate.UnexpectedDrop
    }
    return TwitchProgressUpdate.Updated(
        updateDrop(progress.dropId) { drop ->
            val required = drop.requiredMinutes.coerceAtLeast(0)
            val reported = progress.currentMinutes.coerceIn(0, required)
            val current = maxOf(drop.currentMinutes.coerceIn(0, required), reported)
            drop.copy(
                currentMinutes = current,
                progress = if (required == 0) 0f else current.toFloat() / required,
                canClaim = required > 0 && current >= required && !drop.isClaimed,
            )
        },
    )
}

internal data class UnlinkedProgressProbe(
    val campaignId: String,
    val startedAt: Instant,
    val checkAt: Instant,
    val baselineMinutes: Int,
    val progressDetected: Boolean = false,
) {
    val checkWindowLabel: String
        get() = Duration.between(startedAt, checkAt).runtimeLabel()

    fun observe(campaign: Campaign, now: Instant): UnlinkedProgressProbeResult {
        val currentMinutes = campaign.unlinkedProbeMinutes
        val progressDetectedNow = !progressDetected && currentMinutes > baselineMinutes
        val updated = if (progressDetectedNow) {
            copy(progressDetected = true)
        } else {
            this
        }
        return if (!updated.progressDetected && !now.isBefore(checkAt)) {
            UnlinkedProgressProbeResult.Skip(
                checkedAt = now,
                elapsedLabel = Duration.between(startedAt, now).runtimeLabel(),
            )
        } else {
            UnlinkedProgressProbeResult.Continue(
                probe = updated,
                progressDetectedNow = progressDetectedNow,
            )
        }
    }

    companion object {
        fun start(campaign: Campaign, settings: AppSettings, now: Instant): UnlinkedProgressProbe {
            val configuredDelay = Duration.ofSeconds(
                settings.watchIntervalSeconds.toLong() * UnlinkedProgressCheckIntervals,
            )
            val checkDelay = configuredDelay
                .coerceAtLeast(MinUnlinkedProgressCheckDelay)
                .coerceAtMost(MaxUnlinkedProgressCheckDelay)
            return UnlinkedProgressProbe(
                campaignId = campaign.id,
                startedAt = now,
                checkAt = now.plus(checkDelay),
                baselineMinutes = campaign.unlinkedProbeMinutes,
            )
        }
    }
}

internal sealed class UnlinkedProgressProbeResult {
    data class Continue(
        val probe: UnlinkedProgressProbe,
        val progressDetectedNow: Boolean,
    ) : UnlinkedProgressProbeResult()

    data class Skip(
        val checkedAt: Instant,
        val elapsedLabel: String,
    ) : UnlinkedProgressProbeResult()
}

internal object ActiveWatchGuard {
    fun shouldStopForExcludedCampaign(settings: AppSettings, campaign: Campaign): Boolean =
        settings.isCampaignExcluded(campaign)
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
        val selectableCampaigns = campaigns.withoutExcludedCampaigns(settings)
        if (!settings.hasGamePriority) {
            val autoCandidates = autoCandidates(settings, selectableCampaigns)
            if (autoCandidates.isNotEmpty()) {
                return CampaignCandidateDecision.Try(
                    mode = CampaignSelectionMode.Auto,
                    candidates = autoCandidates,
                    task = "Auto Mode selecting campaign",
                    detail = "No game priority is set.",
                )
            }
            return unlinkedDecision(
                settings = settings,
                campaigns = selectableCampaigns,
                task = "Trying unlinked games",
                detail = "Auto Mode found no linked active campaign with remaining or claimable drops.",
            ) ?: CampaignCandidateDecision.Idle(
                task = "No available campaign can be mined",
                detail = "Auto Mode found no linked active campaign with remaining or claimable drops.",
            )
        }

        val priorityCandidates = prioritizedCandidates(settings, selectableCampaigns)
        if (priorityCandidates.isNotEmpty()) {
            return CampaignCandidateDecision.Try(
                mode = CampaignSelectionMode.Prioritized,
                candidates = priorityCandidates,
                task = "Selecting prioritized campaign",
                detail = "Trying prioritized games in order.",
            )
        }

        prioritizedUnlinkedDecision(
            settings = settings,
            campaigns = selectableCampaigns,
            task = "Trying prioritized unlinked games",
            detail = "No prioritized linked campaign has remaining work; checking prioritized unlinked games.",
        )?.let { return it }

        if (prioritizedGamesComplete(settings, selectableCampaigns)) {
            if (settings.fallbackToAutoWhenPrioritizedComplete) {
                val autoCandidates = autoFallbackCandidates(settings, selectableCampaigns)
                if (autoCandidates.isNotEmpty()) {
                    return CampaignCandidateDecision.Try(
                        mode = CampaignSelectionMode.AutoFallbackPrioritiesComplete,
                        candidates = autoCandidates,
                        task = "Prioritized games complete; using Auto Mode",
                        detail = "All prioritized games are complete.",
                    )
                }
                return unlinkedDecision(
                    settings = settings,
                    campaigns = selectableCampaigns,
                    task = "Prioritized games complete; trying unlinked games",
                    detail = "Auto Mode fallback found no other linked eligible campaign.",
                ) ?: CampaignCandidateDecision.Idle(
                    task = "All prioritized games are complete",
                    detail = "Auto Mode fallback is enabled, but no other eligible campaign is available.",
                    activityTitle = "Priority mining idle",
                )
            }

            return CampaignCandidateDecision.Idle(
                task = "All prioritized games are complete",
                detail = "Auto Mode fallback for completed prioritized games is disabled.",
                activityTitle = "Priority mining idle",
            )
        }

        if (prioritizedGamesCompleteOrExcluded(settings, campaigns)) {
            if (settings.fallbackToAutoWhenPrioritizedComplete) {
                val autoCandidates = autoFallbackCandidates(settings, selectableCampaigns)
                if (autoCandidates.isNotEmpty()) {
                    return CampaignCandidateDecision.Try(
                        mode = CampaignSelectionMode.AutoFallbackPrioritiesComplete,
                        candidates = autoCandidates,
                        task = "Prioritized campaigns excluded; using Auto Mode",
                        detail = "Prioritized campaigns are excluded by user choice.",
                    )
                }
                return unlinkedDecision(
                    settings = settings,
                    campaigns = selectableCampaigns,
                    task = "Prioritized campaigns excluded; trying unlinked games",
                    detail = "Auto Mode fallback found no other linked eligible campaign.",
                ) ?: CampaignCandidateDecision.Idle(
                    task = "Prioritized campaigns are excluded",
                    detail = "Auto Mode fallback is enabled, but no other eligible campaign is available.",
                    activityTitle = "Priority mining idle",
                )
            }

            return CampaignCandidateDecision.Idle(
                task = "Prioritized campaigns are excluded",
                detail = "Auto Mode fallback for unavailable prioritized campaigns is disabled.",
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
        val selectableCampaigns = campaigns.withoutExcludedCampaigns(settings)
        if (!settings.hasGamePriority) {
            return noChannelDecision(CampaignSelectionMode.Auto)
        }
        if (!settings.fallbackToAutoWhenNoPrioritizedChannel) {
            return prioritizedUnlinkedDecision(
                settings = settings,
                campaigns = selectableCampaigns,
                task = "No prioritized live channel; trying prioritized unlinked games",
                detail = "Auto Mode fallback is disabled; checking prioritized unlinked games only.",
            ) ?: CampaignCandidateDecision.Idle(
                task = "No prioritized games have eligible live channels",
                detail = "Auto Mode fallback for prioritized games without live channels is disabled.",
                activityTitle = "Priority mining idle",
                retry = CampaignIdleRetry.WatchInterval,
            )
        }

        val autoCandidates = autoFallbackCandidates(settings, selectableCampaigns)
        if (autoCandidates.isNotEmpty()) {
            return CampaignCandidateDecision.Try(
                mode = CampaignSelectionMode.AutoFallbackNoPrioritizedChannel,
                candidates = autoCandidates,
                task = "No prioritized live channel; using Auto Mode",
                detail = "No prioritized games currently have eligible live channels.",
            )
        }
        return unlinkedDecision(
            settings = settings,
            campaigns = selectableCampaigns,
            task = "No prioritized live channel; trying unlinked games",
            detail = "Auto Mode fallback found no other linked eligible campaign.",
        ) ?: CampaignCandidateDecision.Idle(
            task = "No prioritized games have eligible live channels",
            detail = "Auto Mode fallback is enabled, but no other eligible campaign is available.",
            activityTitle = "Priority mining idle",
            retry = CampaignIdleRetry.WatchInterval,
        )
    }

    fun afterNoChannelDecision(
        settings: AppSettings,
        campaigns: List<Campaign>,
        mode: CampaignSelectionMode,
    ): CampaignCandidateDecision {
        val selectableCampaigns = campaigns.withoutExcludedCampaigns(settings)
        return when (mode) {
            CampaignSelectionMode.Prioritized ->
                afterNoPrioritizedChannelDecision(settings, selectableCampaigns)

            CampaignSelectionMode.Auto ->
                unlinkedDecision(
                    settings = settings,
                    campaigns = selectableCampaigns,
                    task = "Trying unlinked games",
                    detail = "Linked Auto Mode campaigns had no eligible live channel.",
                ) ?: noChannelDecision(mode)

            CampaignSelectionMode.AutoFallbackPrioritiesComplete ->
                unlinkedDecision(
                    settings = settings,
                    campaigns = selectableCampaigns,
                    task = "Auto Mode fallback trying unlinked games",
                    detail = "Prioritized games are complete, and linked fallback campaigns had no eligible live channel.",
                ) ?: noChannelDecision(mode)

            CampaignSelectionMode.AutoFallbackNoPrioritizedChannel ->
                unlinkedDecision(
                    settings = settings,
                    campaigns = selectableCampaigns,
                    task = "Auto Mode fallback trying unlinked games",
                    detail = "Prioritized and linked fallback campaigns had no eligible live channel.",
                ) ?: noChannelDecision(mode)

            CampaignSelectionMode.Unlinked -> noChannelDecision(mode)
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

            CampaignSelectionMode.Unlinked -> CampaignCandidateDecision.Idle(
                task = "No unlinked games have eligible live channels",
                detail = "Unlinked game watching is enabled, but no unlinked game has an eligible live channel.",
                activityTitle = "Unlinked games idle",
                retry = CampaignIdleRetry.WatchInterval,
            )
        }

    fun prioritizedCandidates(settings: AppSettings, campaigns: List<Campaign>): List<Campaign> {
        val earnableCampaigns = campaigns
            .withoutExcludedCampaigns(settings)
            .filter { it.canEarnLocally }
        return settings.selectedGamePriority.flatMap { gameName ->
            earnableCampaigns.filter { campaign ->
                campaign.gameName.equals(gameName, ignoreCase = true)
            }.sortedBy { it.remainingMinutes }
        }
    }

    fun autoFallbackCandidates(settings: AppSettings, campaigns: List<Campaign>): List<Campaign> =
        autoCandidates(settings, campaigns)
            .filter { campaign -> !settings.isGamePrioritized(campaign.gameName) }

    fun unlinkedCandidates(settings: AppSettings, campaigns: List<Campaign>): List<Campaign> =
        if (settings.allowWatchingUnlinkedGames) {
            campaigns
                .withoutExcludedCampaigns(settings)
                .filter { it.canTryUnlinkedLocally }
                .sortedWith(
                    compareBy<Campaign> { settings.gamePriorityIndex(it.gameName) ?: Int.MAX_VALUE }
                        .thenBy { it.remainingMinutes },
                )
        } else {
            emptyList()
        }

    fun prioritizedUnlinkedCandidates(settings: AppSettings, campaigns: List<Campaign>): List<Campaign> =
        unlinkedCandidates(settings, campaigns)
            .filter { campaign -> settings.isGamePrioritized(campaign.gameName) }

    fun prioritizedGamesComplete(settings: AppSettings, campaigns: List<Campaign>): Boolean {
        if (!settings.hasGamePriority) {
            return false
        }
        val selectableCampaigns = campaigns.withoutExcludedCampaigns(settings)
        return settings.selectedGamePriority.all { gameName ->
            val gameCampaigns = selectableCampaigns.filter { campaign ->
                campaign.gameName.equals(gameName, ignoreCase = true)
            }
            gameCampaigns.isNotEmpty() && gameCampaigns.all { it.isLocallyComplete }
        }
    }

    private fun prioritizedGamesCompleteOrExcluded(settings: AppSettings, campaigns: List<Campaign>): Boolean {
        if (!settings.hasGamePriority) {
            return false
        }
        val hasExcludedPrioritizedCampaign = campaigns.any { campaign ->
            settings.isGamePrioritized(campaign.gameName) && settings.isCampaignExcluded(campaign)
        }
        if (!hasExcludedPrioritizedCampaign) {
            return false
        }
        return settings.selectedGamePriority.all { gameName ->
            val gameCampaigns = campaigns.filter { campaign ->
                campaign.gameName.equals(gameName, ignoreCase = true)
            }
            gameCampaigns.isNotEmpty() &&
                gameCampaigns.all { campaign ->
                    settings.isCampaignExcluded(campaign) || campaign.isLocallyComplete
                }
        }
    }

    private fun autoCandidates(settings: AppSettings, campaigns: List<Campaign>): List<Campaign> =
        campaigns
            .withoutExcludedCampaigns(settings)
            .filter { it.canEarnLocally }

    private fun unlinkedDecision(
        settings: AppSettings,
        campaigns: List<Campaign>,
        task: String,
        detail: String,
    ): CampaignCandidateDecision.Try? {
        return unlinkedCandidates(settings, campaigns)
            .takeIf { it.isNotEmpty() }
            ?.let {
                CampaignCandidateDecision.Try(
                    mode = CampaignSelectionMode.Unlinked,
                    candidates = it,
                    task = task,
                    detail = detail,
                )
            }
    }

    private fun prioritizedUnlinkedDecision(
        settings: AppSettings,
        campaigns: List<Campaign>,
        task: String,
        detail: String,
    ): CampaignCandidateDecision.Try? =
        prioritizedUnlinkedCandidates(settings, campaigns)
            .takeIf { it.isNotEmpty() }
            ?.let {
                CampaignCandidateDecision.Try(
                    mode = CampaignSelectionMode.Unlinked,
                    candidates = it,
                    task = task,
                    detail = detail,
                )
            }
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
    Unlinked,
}

internal enum class CampaignIdleRetry {
    InventoryRefresh,
    WatchInterval,
}

private val CampaignSelectionMode.isAutoFallback: Boolean
    get() = this == CampaignSelectionMode.AutoFallbackPrioritiesComplete ||
        this == CampaignSelectionMode.AutoFallbackNoPrioritizedChannel

private val CampaignSelectionMode.isUnlinked: Boolean
    get() = this == CampaignSelectionMode.Unlinked

private fun CampaignSelectionMode.selectionTask(candidate: Campaign): String =
    when (this) {
        CampaignSelectionMode.Auto -> "Auto Mode selected ${candidate.gameName}"
        CampaignSelectionMode.Prioritized -> "Selected ${candidate.gameName}"
        CampaignSelectionMode.AutoFallbackPrioritiesComplete,
        CampaignSelectionMode.AutoFallbackNoPrioritizedChannel -> "Auto Mode selected ${candidate.gameName}"
        CampaignSelectionMode.Unlinked -> "Trying unlinked game ${candidate.gameName}"
    }

private fun CampaignSelectionMode.noChannelDetail(candidate: Campaign): String =
    when (this) {
        CampaignSelectionMode.Auto -> "${candidate.gameName}; trying next Auto Mode campaign."
        CampaignSelectionMode.Prioritized -> "${candidate.gameName}; trying next prioritized campaign."
        CampaignSelectionMode.AutoFallbackPrioritiesComplete,
        CampaignSelectionMode.AutoFallbackNoPrioritizedChannel ->
            "${candidate.gameName}; trying next Auto Mode fallback campaign."
        CampaignSelectionMode.Unlinked -> "${candidate.gameName}; trying next unlinked game."
    }

private fun CampaignCandidateDecision.Idle.retryDelayMillis(settings: AppSettings): Long =
    when (retry) {
        CampaignIdleRetry.InventoryRefresh -> settings.inventoryRefreshMinutes * 60_000L
        CampaignIdleRetry.WatchInterval -> settings.watchIntervalSeconds * 1000L
    }

private fun Duration.runtimeLabel(): String {
    val totalSeconds = seconds.coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val secondsPart = totalSeconds % 60
    return when {
        minutes > 0L && secondsPart == 0L -> "$minutes min"
        minutes > 0L -> "$minutes min ${secondsPart}s"
        else -> "${totalSeconds}s"
    }
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

private data class CompatibleChannelSearch(
    val channels: List<Channel>,
    val task: String,
    val detail: String,
)

private data class ChannelControlRequest(
    val id: Long = 0L,
    val selectedChannelId: Long? = null,
)

private data class CampaignLoadResult(
    val campaigns: List<Campaign>,
    val settings: AppSettings,
    val failure: String? = null,
)

private sealed interface WatchAttemptResult {
    data object Accepted : WatchAttemptResult
    data object Rejected : WatchAttemptResult
    data class Failed(val message: String) : WatchAttemptResult
}

private class ChannelDiscoveryUnavailableException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

internal object EligibleChannelSelector {
    fun select(channels: List<Channel>, skippedChannelIds: Set<Long>): Channel? =
        candidates(channels, skippedChannelIds).firstOrNull()

    fun candidates(channels: List<Channel>, skippedChannelIds: Set<Long>): List<Channel> =
        channels
            .asSequence()
            .filter { channel ->
                channel.online &&
                    channel.dropsEnabled &&
                    channel.id > 0L &&
                    !channel.broadcastId.isNullOrBlank() &&
                    channel.id !in skippedChannelIds
            }
            .sortedWith(
                compareByDescending<Channel> { it.aclBased }
                    .thenByDescending { it.viewers ?: -1 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
            .toList()
}

internal object ChannelPickerSelection {
    fun findCompatibleChannel(
        channels: List<Channel>,
        channelId: Long,
    ): Channel? = EligibleChannelSelector.candidates(
        channels = channels,
        skippedChannelIds = emptySet(),
    ).firstOrNull { channel -> channel.id == channelId }
}

internal object RuntimeRetryBackoff {
    private val MaxDelay: Duration = Duration.ofMinutes(5)

    fun delayFor(consecutiveFailures: Int): Duration {
        val exponent = (consecutiveFailures.coerceAtLeast(1) - 1).coerceAtMost(5)
        val seconds = 15L shl exponent
        return Duration.ofSeconds(seconds).coerceAtMost(MaxDelay)
    }
}

private val Campaign.isLocallyComplete: Boolean
    get() = drops.isNotEmpty() && drops.all { drop -> drop.isClaimed }

private val Campaign.unlinkedProbeMinutes: Int
    get() = drops.sumOf { it.currentMinutes.coerceAtLeast(0) }

private fun List<Campaign>.withoutSkippedUnlinkedCampaigns(
    skippedCampaignIds: Set<String>,
): List<Campaign> =
    filterNot { campaign ->
        campaign.id in skippedCampaignIds && campaign.canTryUnlinkedLocally
    }

private fun List<Campaign>.withoutExcludedCampaigns(settings: AppSettings): List<Campaign> =
    filterNot { settings.isCampaignExcluded(it) }

private fun List<Campaign>.normalizedCampaignIds(): Set<String> =
    map { it.id.trim().lowercase() }
        .filter { it.isNotBlank() }
        .toSet()

private fun List<Campaign>.selectionLabel(limit: Int): String {
    val labels = take(limit).map { campaign ->
        "${campaign.gameName}: ${campaign.name.ifBlank { "Unnamed campaign" }}"
    }
    val suffix = if (size > limit) ", +${size - limit} more" else ""
    return labels.joinToString() + suffix
}

private fun List<Campaign>.availablePriorityGameNames(): Set<String> =
    filterNot { it.expired }
        .map { it.gameName.trim() }
        .filter { it.isNotBlank() }
        .toSet()

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

private fun List<Channel>.markWatching(channelId: Long): List<Channel> =
    map { channel -> channel.copy(watching = channel.id == channelId) }

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

private fun Throwable.throwIfInvalidToken() {
    if (this is TwitchApiException && type == TwitchApiErrorType.InvalidToken) {
        throw this
    }
}
