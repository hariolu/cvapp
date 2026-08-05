package com.cvapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Watches for network changes (Wi-Fi connect/disconnect, switching to
 * mobile data, etc.) and notifies [onNetworkChanged] so the app can
 * re-evaluate whether the local HA instance is reachable.
 */
class NetworkMonitor(context: Context, private val onNetworkChanged: () -> Unit) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()
        override fun onLost(network: Network) = onNetworkChanged()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onNetworkChanged()
    }

    /** True only when the active network is Wi-Fi — local probing is skipped otherwise. */
    fun isOnWifi(): Boolean {
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun start() {
        if (registered) return
        registered = true
        runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback) }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}
