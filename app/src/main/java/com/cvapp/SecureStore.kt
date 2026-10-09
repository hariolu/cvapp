package com.cvapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for the WireGuard private key and VPN refresh token —
 * meaningfully more sensitive than the LLAT already in [MainActivity]'s plain
 * `prefs`, since a leaked one grants raw network access into the house, not
 * just the HA panel API surface.
 *
 * `androidx.security:security-crypto` (EncryptedSharedPreferences) never left
 * alpha and was deprecated in 2025 with no maintained successor from Google —
 * not something to add as this app's fourth dependency when the platform
 * Keystore already does the whole job in ~40 lines. Values are encrypted
 * individually and stored as plain strings in the existing `prefs` file; only
 * the AES key itself lives in AndroidKeyStore (hardware-backed where the
 * device supports it, and never extractable either way).
 */
class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences("cvapp_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS  = "cvapp_vpn_secret_key"
        private const val TRANSFORM  = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_LAST_REFRESH = "vpn_last_refresh"
    }

    private val secretKey: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    /** iv (12 bytes) + ciphertext, base64'd — the IV travels with the value since GCM needs a fresh one per encryption. */
    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val ciphertext = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    // commit(), not apply(). Everything written through here is the difference
    // between walking back into the app and being sent to the bot for a code,
    // and apply() only promises the write happens eventually. A few milliseconds
    // a handful of times a month is not a price worth arguing about.
    private fun put(key: String, value: String?) {
        if (value == null) { prefs.edit().remove(key).commit(); return }
        prefs.edit().putString(key, encrypt(value)).commit()
    }

    private fun get(key: String): String? =
        prefs.getString(key, null)?.let { decrypt(it) }

    // ── WireGuard device identity ────────────────────────────────────────────
    // Generated once on first bootstrap and kept for the app's lifetime — the
    // private key never leaves this device, cv-auth only ever sees the pubkey.
    var wgPrivateKey: String?
        get() = get("vpn_wg_private_key")
        set(v) = put("vpn_wg_private_key", v)

    // ── VPN session ───────────────────────────────────────────────────────────
    var refreshToken: String?
        get() = get("vpn_refresh_token")
        set(v) = put("vpn_refresh_token", v)

    // Not secret on their own, but they only mean anything alongside the two
    // values above, and keeping all four together avoids a torn read after a
    // bootstrap that fails partway through.
    var serverPubkey: String?
        get() = prefs.getString("vpn_server_pubkey", null)
        set(v) = prefs.edit().putString("vpn_server_pubkey", v).apply()

    var endpoint: String?
        get() = prefs.getString("vpn_endpoint", null)
        set(v) = prefs.edit().putString("vpn_endpoint", v).apply()

    var tunnelIp: String?
        get() = prefs.getString("vpn_tunnel_ip", null)
        set(v) = prefs.edit().putString("vpn_tunnel_ip", v).apply()

    var tunnelHost: String?
        get() = prefs.getString("vpn_tunnel_host", null)
        set(v) = prefs.edit().putString("vpn_tunnel_host", v).apply()

    var lanCidr: String?
        get() = prefs.getString("vpn_lan_cidr", null)
        set(v) = prefs.edit().putString("vpn_lan_cidr", v).apply()

    /**
     * A whole session in one editor and one commit.
     *
     * Six separate writes can be interrupted between any two of them, and a
     * device left holding a new token beside a stale endpoint is worse off than
     * one holding neither: [hasPeer] would say yes to a set of values that never
     * belonged together.
     */
    fun save(config: CvAuthApi.VpnConfig) {
        prefs.edit()
            .putString("vpn_refresh_token", encrypt(config.refreshToken))
            .putString("vpn_server_pubkey", config.serverPubkey)
            .putString("vpn_endpoint",      config.endpoint)
            .putString("vpn_tunnel_ip",     config.tunnelIp)
            .putString("vpn_tunnel_host",   config.tunnelHost)
            .putString("vpn_lan_cidr",      config.lanCidr)
            .putLong(KEY_LAST_REFRESH, System.currentTimeMillis())
            .commit()
    }

    /**
     * How long since cv-auth last vouched for this session.
     *
     * [Long.MAX_VALUE] when that cannot be known — an install updated into this
     * version holds a token but no note of when it was last renewed, and a clock
     * that has moved backwards makes the stored moment look like the future.
     * Neither is a young session; both should renew at the first chance.
     */
    fun sessionAgeMs(): Long {
        val last = prefs.getLong(KEY_LAST_REFRESH, 0L)
        if (last <= 0L) return Long.MAX_VALUE
        val age = System.currentTimeMillis() - last
        return if (age < 0L) Long.MAX_VALUE else age
    }

    fun hasPeer(): Boolean = wgPrivateKey != null && refreshToken != null && serverPubkey != null

    /**
     * Everything that lets this phone in from outside: the WireGuard key and
     * the session. cv-auth is not told — it has no way to be — but its peer
     * is useless without the key, which existed only here, and the session
     * lapses there after a month unused. The house's address stays: the page
     * is loaded from it at home too, and Home Assistant's sign-in lives there.
     */
    fun forget() {
        prefs.edit()
            .remove("vpn_wg_private_key")
            .remove("vpn_refresh_token")
            .remove("vpn_server_pubkey")
            .remove("vpn_endpoint")
            .remove("vpn_tunnel_ip")
            .remove("vpn_lan_cidr")
            .remove(KEY_LAST_REFRESH)
            .commit()
    }

    fun clearSession() {
        // Deliberately keeps wgPrivateKey — the device identity survives a
        // revoked/expired session; only bootstrap ever needs to replace it.
        refreshToken = null
        prefs.edit().remove(KEY_LAST_REFRESH).commit()
    }
}
