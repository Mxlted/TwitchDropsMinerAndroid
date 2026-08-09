package com.nathan.twitchdropsminer.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathan.twitchdropsminer.android.ui.AppViewModel
import com.nathan.twitchdropsminer.android.ui.TwitchDropsMinerApp

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        AppViewModel.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    viewModel.startMiningForeground()
                } else {
                    viewModel.notificationPermissionDenied()
                }
            }

            TwitchDropsMinerApp(
                uiState = uiState,
                onCompleteOnboarding = viewModel::completeOnboarding,
                onStartLogin = viewModel::startLogin,
                onOpenActivation = ::openUrl,
                onRefresh = viewModel::refreshNow,
                onStartMining = {
                    if (uiState.settings.runInForeground) {
                        if (needsNotificationPermission()) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.startMiningForeground()
                        }
                    } else {
                        viewModel.startMiningInApp()
                    }
                },
                onStopMining = viewModel::stopMining,
                onFindNewChannel = viewModel::findNewChannel,
                onSelectChannel = viewModel::selectChannel,
                onToggleGamePriority = viewModel::toggleGamePriority,
                onMoveGamePriority = viewModel::moveGamePriority,
                onSetGamePriority = viewModel::setGamePriority,
                onClearGamePriority = viewModel::clearGamePriority,
                onSetCampaignExclusion = viewModel::setCampaignExclusion,
                onClearLogs = viewModel::clearLogs,
                onRunInForegroundChanged = viewModel::setRunInForeground,
                onWatchIntervalChanged = viewModel::setWatchInterval,
                onInventoryRefreshChanged = viewModel::setInventoryRefresh,
                onKeepActiveScreenModeChanged = viewModel::setKeepActiveScreenMode,
                onEnterDimScreen = viewModel::enterDimScreen,
                onExitDimScreen = viewModel::exitDimScreen,
                onFallbackToOtherGamesChanged = viewModel::setFallbackToOtherGames,
                onMoveAutoModePriority = viewModel::moveAutoModePriority,
                onAdvancedBackendModeChanged = viewModel::setAdvancedBackendMode,
                onSaveBackendUrl = viewModel::saveBackendUrl,
                onDebugLoggingChanged = viewModel::setDebugLogging,
                onOpenBatterySettings = ::openBatterySettings,
                onResetSettings = viewModel::resetSettings,
                onResetSession = viewModel::resetSession,
            )
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

    private fun openUrl(url: String) {
        if (url.isBlank()) {
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, url.trim().toUri()))
        }
    }

    private fun openBatterySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.recoverCatching {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
