package com.cvapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches for networks coming and going (Wi-Fi joined or left, mobile data)
 * and notifies [onNetworkChanged] so the app can ask again which way leads
 * home.
 *
 * Only arrivals and departures, and never the app's own tunnel: a network's
 * capabilities change every few seconds (signal strength, bandwidth), and the
 * tunnel coming up or down is the app's own doing. Each of those used to set
 * off a fresh look for the house, which is how the loading screen came to
 * swing between Local and Remote while the house was not answering.
 */
class NetworkMonitor(context: Context, private val onNetworkChanged: () -> Unit) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false

    // The Wi-Fi networks up now. The active network says Wi-Fi only while
    // nothing sits on top of it, and the tunnel does.
    private val wifi = ConcurrentHashMap.newKeySet<Network>()

    private val anyCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()
        override fun onLost(network: Network) = onNetworkChanged()
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { wifi.add(network) }
        override fun onLost(network: Network) { wifi.remove(network) }
    }

    /** True when a Wi-Fi network is up — local probing is skipped otherwise. */
    fun isOnWifi(): Boolean {
        if (wifi.isNotEmpty()) return true
        // Before the callback has said anything: the active network, as it was.
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun start() {
        if (registered) return
        registered = true
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), anyCallback)
        }
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), wifiCallback)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { cm.unregisterNetworkCallback(anyCallback) }
        runCatching { cm.unregisterNetworkCallback(wifiCallback) }
        wifi.clear()
    }
}
