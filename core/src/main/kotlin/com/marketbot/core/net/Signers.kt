package com.marketbot.core.net

import okhttp3.HttpUrl
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.RSABlindedEngine
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.signers.PSSSigner
import java.security.SecureRandom
import java.util.Base64

/**
 * Kalshi's request signature: RSA-PSS over SHA-256 (MGF1 SHA-256, salt length
 * = digest length) of `timestamp_ms + METHOD + path`, where path excludes the
 * query string. Matches weatherbot's `KalshiAuth.create_auth_headers`.
 */
class KalshiSigner private constructor(
    private val keyId: String,
    private val signBytes: (ByteArray) -> ByteArray,
    private val nowMs: () -> Long,
) : RequestSigner {
    constructor(keyId: String, pem: String, nowMs: () -> Long = { System.currentTimeMillis() }) : this(keyId, pssSigner(parseRsaPrivateKey(pem)), nowMs)

    override fun headers(method: String, url: HttpUrl): Map<String, String> {
        val ts = nowMs().toString()
        return mapOf(
            "KALSHI-ACCESS-KEY" to keyId,
            "KALSHI-ACCESS-SIGNATURE" to sign("$ts${method.uppercase()}${url.encodedPath}"),
            "KALSHI-ACCESS-TIMESTAMP" to ts,
        )
    }

    fun sign(preimage: String): String = Base64.getEncoder().encodeToString(signBytes(preimage.toByteArray(Charsets.UTF_8)))

    companion object {
        /**
         * A signer whose RSA-PSS operation is done elsewhere, e.g. by a key held in
         * the Android Keystore that never leaves secure hardware. [signBytes] must be
         * RSA-PSS, SHA-256, MGF1 SHA-256, 32-byte salt.
         */
        fun external(keyId: String, signBytes: (ByteArray) -> ByteArray, nowMs: () -> Long = { System.currentTimeMillis() }) =
            KalshiSigner(keyId, signBytes, nowMs)

        private fun pssSigner(key: RSAPrivateCrtKeyParameters): (ByteArray) -> ByteArray {
            val random = SecureRandom()
            return { bytes ->
                val signer = PSSSigner(RSABlindedEngine(), SHA256Digest(), 32)
                signer.init(true, ParametersWithRandom(key, random))
                signer.update(bytes, 0, bytes.size)
                signer.generateSignature()
            }
        }

        /** Accepts PKCS#1 (`BEGIN RSA PRIVATE KEY`, what Kalshi hands out) and PKCS#8 (`BEGIN PRIVATE KEY`). */
        fun parseRsaPrivateKey(pem: String): RSAPrivateCrtKeyParameters {
            val text = pem.trim()
            require(text.startsWith("-----BEGIN ")) { "This does not look like a PEM private key." }
            require(!text.contains("ENCRYPTED")) { "The key is password-protected; export it without a password." }
            val der = Base64.getMimeDecoder().decode(
                text.lines().filterNot { it.startsWith("-----") }.joinToString(""))
            val rsa = if (text.contains("BEGIN RSA PRIVATE KEY")) RSAPrivateKey.getInstance(der)
            else RSAPrivateKey.getInstance(PrivateKeyInfo.getInstance(der).parsePrivateKey())
            return RSAPrivateCrtKeyParameters(rsa.modulus, rsa.publicExponent, rsa.privateExponent,
                rsa.prime1, rsa.prime2, rsa.exponent1, rsa.exponent2, rsa.coefficient)
        }
    }
}

/**
 * Polymarket US request signature: Ed25519 over `timestamp_ms + METHOD + path`
 * (no query), base64. The secret is the base64 key from polymarket.us/developer,
 * either the 32-byte seed or the 64-byte seed+public form. Matches the
 * official `polymarket-us` SDK's `create_auth_headers`.
 */
class PolymarketSigner(
    private val keyId: String,
    secretKeyB64: String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : RequestSigner {
    private val key: Ed25519PrivateKeyParameters

    init {
        val raw = try {
            Base64.getDecoder().decode(secretKeyB64.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The Polymarket secret key is not valid base64.")
        }
        require(raw.size == 32 || raw.size == 64) { "The Polymarket secret key must decode to 32 or 64 bytes (got ${raw.size})." }
        key = Ed25519PrivateKeyParameters(raw, 0)
    }

    override fun headers(method: String, url: HttpUrl): Map<String, String> {
        val ts = nowMs().toString()
        return mapOf(
            "X-PM-Access-Key" to keyId,
            "X-PM-Timestamp" to ts,
            "X-PM-Signature" to sign("$ts${method.uppercase()}${url.encodedPath}"),
        )
    }

    fun sign(message: String): String {
        val s = Ed25519Signer()
        s.init(true, key)
        val b = message.toByteArray(Charsets.UTF_8)
        s.update(b, 0, b.size)
        return Base64.getEncoder().encodeToString(s.generateSignature())
    }
}
