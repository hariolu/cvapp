package com.cvapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to cv-auth's /vpn/bootstrap and /vpn/refresh — the OTP-gated door to
 * a WireGuard peer. Both calls happen over plain HTTPS to the droplet, never
 * through the tunnel itself: bootstrap can't depend on a tunnel that doesn't exist yet,
 * and refresh is deliberately kept that way too, so a stale/broken tunnel
 * can never block the retry that would fix it.
 */
object CvAuthApi {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS    = 10_000

    data class VpnConfig(
        val refreshToken: String,
        val serverPubkey: String,
        val endpoint: String,
        val tunnelIp: String,
        val tunnelHost: String,
        val lanCidr: String,
    )

    /**
     * Three outcomes, not two. "cv-auth said no" and "cv-auth never answered"
     * used to arrive here as the same failure, so the caller had no way to tell
     * a dead session from one bad minute of signal — and treated both as dead.
     * Only [Rejected] is the service's own verdict on the token.
     */
    sealed class Result {
        data class Ok(val config: VpnConfig) : Result()

        sealed class Failed : Result() {
            abstract val message: String
        }

        /** The service answered, and the answer was no. */
        data class Rejected(val status: Int, override val message: String) : Failed()

        /** No answer worth trusting: offline, timed out, banned for an hour, or the service itself is down. */
        data class Unreachable(override val message: String) : Failed()
    }

    /** [code] is the 6-digit Telegram OTP; [pubkey] is this device's WireGuard public key. */
    suspend fun bootstrap(code: String, pubkey: String): Result =
        post("${BuildConfig.LOGIN_URL}vpn/bootstrap", JSONObject().apply {
            put("code", code)
            put("pubkey", pubkey)
        })

    suspend fun refresh(refreshToken: String): Result =
        post("${BuildConfig.LOGIN_URL}vpn/refresh", JSONObject().apply {
            put("refresh_token", refreshToken)
        })

    private suspend fun post(urlStr: String, body: JSONObject): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                try {
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                    val ok = conn.responseCode in 200..299
                    val text = (if (ok) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText().orEmpty()

                    if (!ok) {
                        val status = conn.responseCode
                        // FastAPI puts the reason in "detail"; "error" is kept for
                        // anything older that still answers the other way.
                        val msg = runCatching {
                            val json = JSONObject(text)
                            json.optString("detail").ifBlank { json.optString("error") }
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $status"
                        // A 5xx is the service having a bad day and a 429 is an
                        // hour in the sin bin — neither is a verdict on this
                        // token, and neither should cost anybody their session.
                        return@runCatching if (status in 400..499 && status != 429)
                            Result.Rejected(status, msg)
                        else Result.Unreachable(msg)
                    }

                    val json = JSONObject(text)
                    Result.Ok(
                        VpnConfig(
                            refreshToken = json.getString("refresh_token"),
                            serverPubkey = json.getString("server_pubkey"),
                            endpoint     = json.getString("endpoint"),
                            tunnelIp     = json.getString("tunnel_ip"),
                            tunnelHost   = json.getString("tunnel_host"),
                            lanCidr      = json.getString("lan_cidr"),
                        )
                    )
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { Result.Unreachable(it.message ?: "Network error") }
        }
}
