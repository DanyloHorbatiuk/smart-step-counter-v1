package com.example.smartstepcounter.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface NetworkMonitor {
    /** True while the default network has validated internet access. */
    val online: StateFlow<Boolean>
}

/** Tracks the default network for the whole process lifetime (registered once in AppContainer). */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(cm.getNetworkCapabilities(cm.activeNetwork).hasInternet())
    override val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                update(caps.hasInternet())
            }

            override fun onLost(network: Network) = update(false)
        })
    }

    private fun update(value: Boolean) {
        if (_online.value != value) Log.d(TAG, "network online=$value")
        _online.value = value
    }

    private fun NetworkCapabilities?.hasInternet() =
        this != null &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private companion object {
        const val TAG = "CloudSync"
    }
}
