package com.nathan.twitchdropsminer.android.di

import android.content.Context
import com.nathan.twitchdropsminer.android.data.local.LogRepository
import com.nathan.twitchdropsminer.android.data.local.SecureSessionStore
import com.nathan.twitchdropsminer.android.data.local.SettingsRepository
import com.nathan.twitchdropsminer.android.data.network.AndroidNetworkStatusProvider
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiClient
import com.nathan.twitchdropsminer.android.runtime.LocalMinerRuntime
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class AppGraph private constructor(context: Context) {
    private val appContext = context.applicationContext

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val settingsRepository = SettingsRepository(appContext)
    val logRepository = LogRepository(appContext)
    val secureSessionStore = SecureSessionStore(appContext)
    val networkStatusProvider = AndroidNetworkStatusProvider(appContext)
    val twitchApiClient = TwitchApiClient(okHttpClient)
    val localMinerRuntime = LocalMinerRuntime(
        settingsRepository = settingsRepository,
        secureSessionStore = secureSessionStore,
        logRepository = logRepository,
        twitchApiClient = twitchApiClient,
        networkStatusProvider = networkStatusProvider,
    )

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun from(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
