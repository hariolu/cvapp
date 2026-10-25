package com.cvapp

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Listens for CV Chat's notifications while the app is closed.
 *
 * No Google: one long-lived stream to the house's relay (ntfy), the topic only
 * this phone and its house know, and every message sealed for this phone. The
 * relay keeps what comes while the phone is away (72 hours) and hands it over
 * on reconnect, from the last message taken.
 *
 * A foreground service, which is what Android asks of an app that keeps a
 * connection open, with its notice on the quietest channel.
 */
class PushService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var conn: HttpURLConnection? = null
    private var network: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SecureStore(this)
        if (!store.pushReady()) {
            stopSelf()
            return START_NOT_STICKY
        }
        Notifier.ensureChannels(this)
        val notice = Notifier.linkNotice(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.LINK_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(Notifier.LINK_ID, notice)
        }
        watchNetwork()
        if (job?.isActive != true) job = scope.launch { listen(store) }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        conn?.disconnect()
        netCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun listen(store: SecureStore) {
        var wait = 2_000L
        while (currentCoroutineContext().isActive && store.pushReady()) {
            val since = store.pushSince
            try {
                val c = URL("${store.pushRelay}/${store.pushTopic}/json" + (since?.let { "?since=$it" } ?: ""))
                    .openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 15_000
                // The relay sends a keepalive every 45 s; silence past this is a dead stream.
                c.readTimeout = 100_000
                c.setRequestProperty("User-Agent", "CVapp/${BuildConfig.VERSION_NAME}")
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                Log.d(TAG, "listening" + if (since != null) " from the last message taken" else "")
                c.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        handle(store, line)
                        wait = 2_000L
                    }
                }
            } catch (e: Exception) {
                // Dropped, timed out or refused: wait, then try again.
                Log.w(TAG, "relay stream ended: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                conn?.disconnect()
                conn = null
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(60_000L)
        }
        stopSelf()
    }

    private fun handle(store: SecureStore, line: String) {
        val event = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (event.optString("event") != "message") return
        store.pushSince = event.optString("id")
        val (priv, pub) = store.pushKeys()
        val payload = PushCrypto.open(priv, pub, event.optString("message"))
        if (payload == null) {
            Log.w(TAG, "a message not sealed for this phone, skipped")
            return
        }
        Notifier.handle(this, payload)
    }

    /** On a new network the old stream is dead though nobody says so: drop it and reconnect now. */
    private fun watchNetwork() {
        if (netCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                if (network != null && network != n) conn?.disconnect()
                network = n
            }
            override fun onLost(n: Network) {
                if (network == n) conn?.disconnect()
            }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
        netCallback = cb
    }

    companion object {
        private const val TAG = "CVapp.push"

        fun start(ctx: Context) {
            if (SecureStore(ctx).pushReady()) {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PushService::class.java))
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PushService::class.java))
        }
    }
}

/** Back to listening after a reboot or an update of the app. */
class PushBoot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PushService.start(ctx)
        }
    }
}
