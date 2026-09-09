package com.cvapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fast reachability probe for the local Home Assistant instance.
 * Runs off the main thread and never throws — connection/DNS/timeout
 * failures all just mean "not reachable".
 */
object HaProbe {
    // Long enough to answer "is this the home Wi-Fi" without making anyone wait
    // for it. A tunnel wants longer — see the callers.
    private const val TIMEOUT_MS = 700

    suspend fun isReachable(url: String, timeoutMs: Int = TIMEOUT_MS): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            try {
                conn.connect()
                // Any HTTP response at all (even 4xx) proves something is listening
                // and answering as HA; only connect/timeout failures mean "down".
                conn.responseCode in 200..499
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }
}
