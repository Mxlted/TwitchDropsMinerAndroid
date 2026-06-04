package com.nathan.twitchdropsminer.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nathan.twitchdropsminer.android.data.backend.BackendUrlValidator
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.LocalLogEntry
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import com.nathan.twitchdropsminer.android.di.AppGraph
import com.nathan.twitchdropsminer.android.service.MinerForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val settings: AppSettings = AppSettings(),
    val snapshot: RuntimeSnapshot = RuntimeSnapshot(),
    val localLogs: List<LocalLogEntry> = emptyList(),
    val isRefreshing: Boolean = false,
    val dimScreenActive: Boolean = false,
    val transientMessage: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = AppGraph.from(application)
    private val mutableState = MutableStateFlow(AppUiState())

    val uiState: StateFlow<AppUiState> = combine(
        mutableState,
        graph.settingsRepository.settings,
        graph.logRepository.entries,
        graph.localMinerRuntime.snapshot,
    ) { state, settings, logs, snapshot ->
        state.copy(settings = settings, localLogs = logs, snapshot = snapshot)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppUiState(),
    )

    init {
        viewModelScope.launch {
            graph.logRepository.load()
            graph.logRepository.append("INFO", "Android local miner app opened")
            graph.localMinerRuntime.bootstrap()
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            graph.settingsRepository.completeOnboarding()
            graph.logRepository.append("INFO", "Onboarding completed")
        }
    }

    fun startLogin() {
        graph.localMinerRuntime.startAuthentication()
    }

    fun refreshNow() {
        viewModelScope.launch {
            mutableState.update { it.copy(isRefreshing = true) }
            runCatching {
                graph.localMinerRuntime.refreshInventoryOnce()
            }.onFailure { error ->
                graph.logRepository.append(
                    "ERROR",
                    "Inventory refresh failed: ${error.message ?: "unknown error"}",
                )
            }
            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    fun startMiningForeground() {
        MinerForegroundService.start(getApplication())
        viewModelScope.launch {
            graph.logRepository.append("INFO", "Foreground local miner start requested")
        }
    }

    fun notificationPermissionDenied() {
        viewModelScope.launch {
            graph.logRepository.append(
                "WARN",
                "Notification permission denied; foreground mining was not started",
            )
        }
    }

    fun startMiningInApp() {
        graph.localMinerRuntime.startMining()
    }

    fun stopMining() {
        graph.localMinerRuntime.stopMining()
        MinerForegroundService.stop(getApplication())
    }

    fun toggleGamePriority(gameName: String) {
        graph.localMinerRuntime.toggleGamePriority(gameName)
    }

    fun moveGamePriority(gameName: String, offset: Int) {
        graph.localMinerRuntime.moveGamePriority(gameName, offset)
    }

    fun setGamePriority(gameName: String, priorityNumber: Int) {
        graph.localMinerRuntime.setGamePriority(gameName, priorityNumber)
    }

    fun clearGamePriority() {
        graph.localMinerRuntime.clearGamePriority()
    }

    fun selectChannel(channelId: Long) {
        graph.localMinerRuntime.selectChannel(channelId)
    }

    fun clearLogs() {
        viewModelScope.launch {
            graph.logRepository.clear()
        }
    }

    fun resetSession() {
        graph.localMinerRuntime.resetSession()
    }

    fun setRunInForeground(enabled: Boolean) {
        viewModelScope.launch {
            graph.settingsRepository.update {
                it.copy(runInForeground = enabled, monitorInForeground = enabled)
            }
        }
    }

    fun setWatchInterval(seconds: Int) {
        viewModelScope.launch {
            graph.settingsRepository.update {
                it.copy(watchIntervalSeconds = seconds, pollIntervalSeconds = seconds)
            }
        }
    }

    fun setInventoryRefresh(minutes: Int) {
        viewModelScope.launch {
            graph.settingsRepository.update { it.copy(inventoryRefreshMinutes = minutes) }
        }
    }

    fun setKeepActiveScreenMode(enabled: Boolean) {
        viewModelScope.launch {
            graph.settingsRepository.update { it.copy(keepActiveScreenMode = enabled) }
            if (!enabled) {
                mutableState.update { it.copy(dimScreenActive = false) }
            }
        }
    }

    fun enterDimScreen() {
        mutableState.update { it.copy(dimScreenActive = true) }
    }

    fun exitDimScreen() {
        mutableState.update { it.copy(dimScreenActive = false) }
    }

    fun setSampleFallback(enabled: Boolean) {
        viewModelScope.launch {
            graph.settingsRepository.update {
                it.copy(useSampleDataFallback = enabled, sampleMode = enabled)
            }
        }
    }

    fun setFallbackToAutoWhenPrioritizedComplete(enabled: Boolean) {
        viewModelScope.launch {
            graph.settingsRepository.update {
                it.copy(fallbackToAutoWhenPrioritizedComplete = enabled)
            }
        }
    }

    fun setFallbackToAutoWhenNoPrioritizedChannel(enabled: Boolean) {
        viewModelScope.launch {
            graph.settingsRepository.update {
                it.copy(fallbackToAutoWhenNoPrioritizedChannel = enabled)
            }
        }
    }

    fun setAdvancedBackendMode(enabled: Boolean) {
        viewModelScope.launch {
            graph.settingsRepository.update { it.copy(advancedBackendMode = enabled) }
        }
    }

    fun saveBackendUrl(url: String) {
        viewModelScope.launch {
            if (url.isBlank()) {
                graph.settingsRepository.update { it.copy(backendUrl = "") }
                graph.secureSessionStore.saveBackendSessionLabel("")
                graph.logRepository.append("INFO", "Optional backend URL cleared")
                return@launch
            }
            BackendUrlValidator.normalize(url)
                .onSuccess { normalized ->
                    graph.settingsRepository.update { it.copy(backendUrl = normalized) }
                    graph.secureSessionStore.saveBackendSessionLabel(normalized)
                    graph.logRepository.append("INFO", "Optional backend URL updated")
                }
                .onFailure { error ->
                    graph.logRepository.append(
                        "WARN",
                        "Optional backend URL was not saved: ${error.message ?: "invalid URL"}",
                    )
                }
        }
    }

    fun setDebugLogging(enabled: Boolean) {
        viewModelScope.launch {
            graph.settingsRepository.update { it.copy(debugLogging = enabled) }
        }
    }

    fun dismissBatteryHelp() {
        viewModelScope.launch {
            graph.settingsRepository.update { it.copy(batteryHelpDismissed = true) }
        }
    }

    fun consumeTransientMessage() {
        mutableState.update { it.copy(transientMessage = null) }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
                return AppViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
