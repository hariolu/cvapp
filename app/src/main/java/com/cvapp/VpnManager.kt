package com.cvapp

import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the single WireGuard tunnel CVapp ever holds — "REMOTE" no longer
 * means the old `login.coyotes.vision` portal, it means this tunnel is up and
 * [BuildConfig.LOCAL_URL] is reachable through it exactly as it would be on
 * the home Wi-Fi. See CvAuthApi for how a [com.cvapp.CvAuthApi.VpnConfig] is
 * obtained; this class only ever brings one up or down.
 */
class VpnManager(context: Context) {

    private val appContext = context.applicationContext
    private val backend = GoBackend(appContext)

    private val tunnel = object : Tunnel {
        override fun getName() = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {}
    }

    companion object {
        private const val TUNNEL_NAME = "cvapp"
    }

    /** Null if VPN permission is already granted; otherwise launch this for the one-time system consent dialog. */
    fun permissionIntent(): android.content.Intent? = VpnService.prepare(appContext)

    fun currentState(): Tunnel.State = backend.getState(tunnel)

    /**
     * Whether the server answered the tunnel since [sinceMs]: a WireGuard
     * handshake. With one, the way to the house is open as far as the server,
     * and a house that still says nothing is the house itself.
     */
    fun handshookSince(sinceMs: Long): Boolean = runCatching {
        val stats = backend.getStatistics(tunnel)
        stats.peers().any { (stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L) >= sinceMs }
    }.getOrDefault(false)

    /** Generates this device's WireGuard identity once and keeps it forever — see [SecureStore]. */
    fun ensureKeyPair(store: SecureStore): String {
        store.wgPrivateKey?.let { return KeyPair(com.wireguard.crypto.Key.fromBase64(it)).publicKey.toBase64() }
        val pair = KeyPair()
        store.wgPrivateKey = pair.privateKey.toBase64()
        return pair.publicKey.toBase64()
    }

    suspend fun up(store: SecureStore): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val privateKey = store.wgPrivateKey ?: error("No device key — bootstrap first")
            val serverPubkey = store.serverPubkey ?: error("No server config — bootstrap first")
            val endpoint = store.endpoint ?: error("No endpoint — bootstrap first")
            val tunnelIp = store.tunnelIp ?: error("No tunnel IP — bootstrap first")
            val lanCidr = store.lanCidr ?: error("No LAN route — bootstrap first")

            val iface = Interface.Builder()
                .parsePrivateKey(privateKey)
                .parseAddresses(tunnelIp)
                .build()

            val peer = Peer.Builder()
                .parsePublicKey(serverPubkey)
                .parseEndpoint(endpoint)
                .parseAllowedIPs(lanCidr)
                .setPersistentKeepalive(25)
                .build()

            val config = Config.Builder()
                .setInterface(iface)
                .addPeer(peer)
                .build()

            backend.setState(tunnel, Tunnel.State.UP, config)
            Unit
        }
    }

    suspend fun down(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
            Unit
        }
    }
}
