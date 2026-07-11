package com.nathan.twitchdropsminer.android.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

interface NetworkStatusProvider {
    val isOnline: StateFlow<Boolean>

    suspend fun awaitOnline() {
        isOnline.filter { it }.first()
    }
}

class AndroidNetworkStatusProvider(context: Context) : NetworkStatusProvider {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutableOnline = MutableStateFlow(connectivityManager.hasValidatedInternet())

    override val isOnline: StateFlow<Boolean> = mutableOnline

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            refresh()
        }

        override fun onLost(network: Network) {
            refresh()
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    private fun refresh() {
        mutableOnline.value = connectivityManager.hasValidatedInternet()
    }
}

private fun ConnectivityManager.hasValidatedInternet(): Boolean {
    val network = activeNetwork ?: return false
    val capabilities = getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
