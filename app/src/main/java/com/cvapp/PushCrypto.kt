package com.cvapp

import com.wireguard.crypto.Curve25519
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opens what the house sealed for this phone (CV Chat's push, the house's
 * cv_chat/push.py).
 *
 * The relay in between sees only this:
 *
 *     "1." + base64url(ephemeral public key 32 | nonce 12 | ciphertext + tag)
 *
 * The house made a fresh X25519 key pair for the message; the shared secret
 * with this phone's key goes through HKDF-SHA256 (salt = ephemeral public key
 * then this phone's public key, info "cv_chat push v1") into an AES-256-GCM key,
 * with "cvpush1" as associated data.
 *
 * No new dependency: WireGuard's keys are X25519 keys, and the tunnel library
 * this app already carries does the curve; AES-GCM and HMAC are the platform's.
 */
object PushCrypto {

    private val INFO = "cv_chat push v1".toByteArray()
    private val AAD = "cvpush1".toByteArray()

    /** A new key pair for this phone: private, then public, 32 bytes each. */
    fun newKeyPair(): Pair<ByteArray, ByteArray> {
        val kp = KeyPair()
        return kp.privateKey.bytes to kp.publicKey.bytes
    }

    /** The notification, or null when it was not sealed for this key or was tampered with. */
    fun open(privateKey: ByteArray, publicKey: ByteArray, wire: String): JSONObject? = runCatching {
        if (!wire.startsWith("1.")) return null
        val raw = Base64.getUrlDecoder().decode(wire.substring(2))
        if (raw.size < 32 + 12 + 16) return null
        val ephemeral = raw.copyOfRange(0, 32)
        val nonce = raw.copyOfRange(32, 44)
        val sealed = raw.copyOfRange(44, raw.size)
        val secret = ByteArray(32).also { Curve25519.eval(it, 0, privateKey, ephemeral) }
        val key = hkdf(secret, ephemeral + publicKey)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        JSONObject(String(cipher.doFinal(sealed), Charsets.UTF_8))
    }.getOrNull()

    /** RFC 5869 with SHA-256, one block: 32 bytes is all AES-256 needs. */
    private fun hkdf(secret: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(secret)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(INFO)
        mac.update(1.toByte())
        return mac.doFinal()
    }
}
