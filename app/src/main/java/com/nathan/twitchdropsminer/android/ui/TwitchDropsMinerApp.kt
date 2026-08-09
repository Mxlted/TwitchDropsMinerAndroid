package com.nathan.twitchdropsminer.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nathan.twitchdropsminer.android.R
import com.nathan.twitchdropsminer.android.data.model.AutoModePriority
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nathan.twitchdropsminer.android.ui.screens.ActivityScreen
import com.nathan.twitchdropsminer.android.ui.screens.DashboardScreen
import com.nathan.twitchdropsminer.android.ui.screens.DropsScreen
import com.nathan.twitchdropsminer.android.ui.screens.LogsScreen
import com.nathan.twitchdropsminer.android.ui.screens.OnboardingScreen
import com.nathan.twitchdropsminer.android.ui.screens.SettingsScreen
import com.nathan.twitchdropsminer.android.ui.theme.TwitchDropsMinerTheme

enum class MainDestination(val label: String, @param:DrawableRes val iconResId: Int) {
    Dashboard("Dashboard", R.drawable.ic_nav_dashboard),
    Drops("Campaigns", R.drawable.ic_nav_campaigns),
    Activity("Activity", R.drawable.ic_nav_activity),
    Logs("Logs", R.drawable.ic_nav_logs),
    Settings("Settings", R.drawable.ic_nav_settings),
}

@Composable
fun TwitchDropsMinerApp(
    uiState: AppUiState,
    onCompleteOnboarding: () -> Unit,
    onStartLogin: () -> Unit,
    onOpenActivation: (String) -> Unit,
    onRefresh: () -> Unit,
    onStartMining: () -> Unit,
    onStopMining: () -> Unit,
    onFindNewChannel: () -> Unit,
    onSelectChannel: (Long) -> Unit,
    onToggleGamePriority: (String) -> Unit,
    onMoveGamePriority: (String, Int) -> Unit,
    onSetGamePriority: (String, Int) -> Unit,
    onClearGamePriority: () -> Unit,
    onSetCampaignExclusion: (Set<String>, Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onRunInForegroundChanged: (Boolean) -> Unit,
    onWatchIntervalChanged: (Int) -> Unit,
    onInventoryRefreshChanged: (Int) -> Unit,
    onKeepActiveScreenModeChanged: (Boolean) -> Unit,
    onEnterDimScreen: () -> Unit,
    onExitDimScreen: () -> Unit,
    onFallbackToOtherGamesChanged: (Boolean) -> Unit,
    onMoveAutoModePriority: (AutoModePriority, Int) -> Unit,
    onAdvancedBackendModeChanged: (Boolean) -> Unit,
    onSaveBackendUrl: (String) -> Unit,
    onDebugLoggingChanged: (Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onResetSettings: () -> Unit,
    onResetSession: () -> Unit,
) {
    TwitchDropsMinerTheme {
        KeepScreenOnEffect(
            enabled = uiState.settings.keepActiveScreenMode &&
                (uiState.dimScreenActive || uiState.snapshot.isRunning),
        )
        when {
            uiState.dimScreenActive -> KeepActiveBlackScreen(onExit = onExitDimScreen)

            !uiState.settings.hasCompletedOnboarding -> {
                OnboardingScreen(onContinue = onCompleteOnboarding)
            }

            else -> {
                MainScaffold(
                    uiState = uiState,
                    onStartLogin = onStartLogin,
                    onOpenActivation = onOpenActivation,
                    onRefresh = onRefresh,
                    onStartMining = onStartMining,
                    onStopMining = onStopMining,
                    onFindNewChannel = onFindNewChannel,
                    onSelectChannel = onSelectChannel,
                    onToggleGamePriority = onToggleGamePriority,
                    onMoveGamePriority = onMoveGamePriority,
                    onSetGamePriority = onSetGamePriority,
                    onClearGamePriority = onClearGamePriority,
                    onSetCampaignExclusion = onSetCampaignExclusion,
                    onClearLogs = onClearLogs,
                    onRunInForegroundChanged = onRunInForegroundChanged,
                    onWatchIntervalChanged = onWatchIntervalChanged,
                    onInventoryRefreshChanged = onInventoryRefreshChanged,
                    onKeepActiveScreenModeChanged = onKeepActiveScreenModeChanged,
                    onEnterDimScreen = onEnterDimScreen,
                    onFallbackToOtherGamesChanged = onFallbackToOtherGamesChanged,
                    onMoveAutoModePriority = onMoveAutoModePriority,
                    onAdvancedBackendModeChanged = onAdvancedBackendModeChanged,
                    onSaveBackendUrl = onSaveBackendUrl,
                    onDebugLoggingChanged = onDebugLoggingChanged,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onResetSettings = onResetSettings,
                    onResetSession = onResetSession,
                )
            }
        }
    }
}

@Composable
private fun MainScaffold(
    uiState: AppUiState,
    onStartLogin: () -> Unit,
    onOpenActivation: (String) -> Unit,
    onRefresh: () -> Unit,
    onStartMining: () -> Unit,
    onStopMining: () -> Unit,
    onFindNewChannel: () -> Unit,
    onSelectChannel: (Long) -> Unit,
    onToggleGamePriority: (String) -> Unit,
    onMoveGamePriority: (String, Int) -> Unit,
    onSetGamePriority: (String, Int) -> Unit,
    onClearGamePriority: () -> Unit,
    onSetCampaignExclusion: (Set<String>, Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onRunInForegroundChanged: (Boolean) -> Unit,
    onWatchIntervalChanged: (Int) -> Unit,
    onInventoryRefreshChanged: (Int) -> Unit,
    onKeepActiveScreenModeChanged: (Boolean) -> Unit,
    onEnterDimScreen: () -> Unit,
    onFallbackToOtherGamesChanged: (Boolean) -> Unit,
    onMoveAutoModePriority: (AutoModePriority, Int) -> Unit,
    onAdvancedBackendModeChanged: (Boolean) -> Unit,
    onSaveBackendUrl: (String) -> Unit,
    onDebugLoggingChanged: (Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onResetSettings: () -> Unit,
    onResetSession: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(MainDestination.Dashboard) }
    val stateHolder = rememberSaveableStateHolder()
    BackHandler(enabled = destination != MainDestination.Dashboard) {
        destination = MainDestination.Dashboard
    }
    val content: @Composable () -> Unit = {
        stateHolder.SaveableStateProvider(destination.name) {
            when (destination) {
                MainDestination.Dashboard -> DashboardScreen(
                    settings = uiState.settings,
                    snapshot = uiState.snapshot,
                    isRefreshing = uiState.isRefreshing,
                    onStartLogin = onStartLogin,
                    onOpenActivation = onOpenActivation,
                    onRefresh = onRefresh,
                    onStartMining = onStartMining,
                    onStopMining = onStopMining,
                    onFindNewChannel = onFindNewChannel,
                    onSelectChannel = onSelectChannel,
                    onEnterDimScreen = onEnterDimScreen,
                )

                MainDestination.Drops -> DropsScreen(
                    settings = uiState.settings,
                    snapshot = uiState.snapshot,
                    onToggleGamePriority = onToggleGamePriority,
                    onMoveGamePriority = onMoveGamePriority,
                    onSetGamePriority = onSetGamePriority,
                    onClearPriority = onClearGamePriority,
                    onSetCampaignExclusion = onSetCampaignExclusion,
                )

                MainDestination.Activity -> ActivityScreen(snapshot = uiState.snapshot)

                MainDestination.Logs -> LogsScreen(
                    localLogs = uiState.localLogs,
                    snapshot = uiState.snapshot,
                    onClearLogs = onClearLogs,
                )

                MainDestination.Settings -> SettingsScreen(
                    settings = uiState.settings,
                    miningActive = uiState.snapshot.isRunning,
                    onRunInForegroundChanged = onRunInForegroundChanged,
                    onWatchIntervalChanged = onWatchIntervalChanged,
                    onInventoryRefreshChanged = onInventoryRefreshChanged,
                    onKeepActiveScreenModeChanged = onKeepActiveScreenModeChanged,
                    onFallbackToOtherGamesChanged = onFallbackToOtherGamesChanged,
                    onMoveAutoModePriority = onMoveAutoModePriority,
                    onAdvancedBackendModeChanged = onAdvancedBackendModeChanged,
                    onSaveBackendUrl = onSaveBackendUrl,
                    onDebugLoggingChanged = onDebugLoggingChanged,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onResetSettings = onResetSettings,
                    onResetSession = onResetSession,
                )
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    MainDestination.entries.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = {
                                Icon(
                                    painter = painterResource(item.iconResId),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    content()
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        MainDestination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = {
                                    Icon(
                                        painter = painterResource(item.iconResId),
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(item.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun KeepScreenOnEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val previous = view.keepScreenOn
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = previous }
    }
}

@Composable
private fun KeepActiveBlackScreen(onExit: () -> Unit) {
    ImmersiveSystemBarsEffect()
    BackHandler(onBack = onExit)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(onExit) {
                detectTapGestures(onTap = { onExit() })
            },
    )
}

@Composable
@Suppress("DEPRECATION")
private fun ImmersiveSystemBarsEffect() {
    val context = LocalContext.current
    val view = LocalView.current

    DisposableEffect(context, view) {
        val window = context.findActivity()?.window
        val controller = window?.let {
            WindowInsetsControllerCompat(it, it.decorView)
        }
        val previousStatusBarColor = window?.statusBarColor
        val previousNavigationBarColor = window?.navigationBarColor
        val previousSystemBarsBehavior = controller?.systemBarsBehavior
        val previousLightStatusBars = controller?.isAppearanceLightStatusBars
        val previousLightNavigationBars = controller?.isAppearanceLightNavigationBars

        window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            it.statusBarColor = AndroidColor.BLACK
            it.navigationBarColor = AndroidColor.BLACK
        }
        controller?.let {
            it.isAppearanceLightStatusBars = false
            it.isAppearanceLightNavigationBars = false
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            controller?.let {
                it.show(WindowInsetsCompat.Type.systemBars())
                previousSystemBarsBehavior?.let { behavior ->
                    it.systemBarsBehavior = behavior
                }
                previousLightStatusBars?.let { lightStatusBars ->
                    it.isAppearanceLightStatusBars = lightStatusBars
                }
                previousLightNavigationBars?.let { lightNavigationBars ->
                    it.isAppearanceLightNavigationBars = lightNavigationBars
                }
            }
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, true)
                previousStatusBarColor?.let { color -> it.statusBarColor = color }
                previousNavigationBarColor?.let { color -> it.navigationBarColor = color }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
