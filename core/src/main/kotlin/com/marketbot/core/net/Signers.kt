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
            require(text.isNotEmpty()) { "Paste the private key, or import the key file Kalshi gave you." }
            require(!text.contains("ENCRYPTED", ignoreCase = true)) { "The key is password-protected; export it without a password." }
            require(!text.contains("PUBLIC KEY", ignoreCase = true)) { "That is a public key. Kalshi needs the private key (the file it gave you when you created the API key)." }
            val p = PemPieces.of(text)
            val der = runCatching { Base64.getDecoder().decode(p.base64) }.getOrElse {
                throw IllegalArgumentException("This does not look like a PEM private key: the key text is not base64. ${p.describe(null)}")
            }
            // Try both layouts whatever the label says: some tools write PKCS#8 under an RSA label.
            val rsa = runCatching { RSAPrivateKey.getInstance(PrivateKeyInfo.getInstance(der).parsePrivateKey()) }
                .recoverCatching { RSAPrivateKey.getInstance(der) }
                .getOrElse { throw IllegalArgumentException("This does not look like an RSA private key. ${p.describe(der.size)} " +
                    "Try the Import PEM file button with the key file Kalshi downloaded: pasting can damage a key.") }
            return RSAPrivateCrtKeyParameters(rsa.modulus, rsa.publicExponent, rsa.privateExponent,
                rsa.prime1, rsa.prime2, rsa.exponent1, rsa.exponent2, rsa.coefficient)
        }

        /**
         * A readable reason the Key ID is wrong, or null. Kalshi's key ids are short
         * (a UUID); the private key pasted into this field is the usual mix-up.
         */
        fun keyIdProblem(keyId: String): String? = when {
            keyId.isBlank() -> "Enter the Key ID shown beside the key on Kalshi."
            "BEGIN" in keyId || keyId.length > 80 -> "That looks like the private key. The Key ID is the short id Kalshi shows beside the key."
            keyId.any { it.isWhitespace() } -> "The Key ID should not contain spaces."
            else -> null
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

/**
 * A pasted PEM taken apart, tolerating what phones and notes apps do to it: spaces
 * inside the marker lines, long dashes (U+2013/2014) in place of hyphens, lines run
 * together or turned into spaces, CRLF, a missing marker, missing `=` padding.
 * [describe] says what was found without printing any of the key itself.
 */
internal class PemPieces private constructor(
    val base64: String, private val label: String?, private val hasBegin: Boolean, private val hasEnd: Boolean,
    private val odd: Map<Char, Int>, private val chars: Int,
) {
    fun describe(bytes: Int?): String = buildString {
        append("(Read ").append(chars).append(" characters; ")
        append(if (hasBegin) "BEGIN line${label?.let { " \"$it\"" } ?: ""}" else "no BEGIN line").append(", ")
        append(if (hasEnd) "END line" else "no END line").append("; ")
        append(base64.length).append(" key characters")
        bytes?.let { append(" = ").append(it).append(" bytes, where a complete 2048-bit key is about 1,190 and a 4096-bit key about 2,350") }
        if (odd.isNotEmpty()) append("; unexpected characters: ").append(odd.entries.joinToString { (c, n) -> "U+%04X x%d".format(c.code, n) })
        append(".)")
    }

    companion object {
        private const val DASH = "[-\u2010-\u2015\u2212\uFE58\uFE63\uFF0D]"
        private val MARKER = Regex("$DASH{2,}\\s*(BEGIN|END)\\s*([A-Za-z0-9 ]*?)\\s*$DASH{2,}")
        private val BARE = Regex("(BEGIN|END)\\s+[A-Z0-9 ]*PRIVATE KEY")

        fun of(text: String): PemPieces {
            val marks = MARKER.findAll(text).toList()
            val begin = marks.firstOrNull { it.groupValues[1] == "BEGIN" }
            val end = marks.lastOrNull { it.groupValues[1] == "END" }
            var body = when {
                begin != null && end != null && end.range.first > begin.range.last -> text.substring(begin.range.last + 1, end.range.first)
                else -> marks.fold(text) { t, m -> t.replace(m.value, " ") }
            }
            body = BARE.replace(body, " ")   // marker words left behind when the dashes were lost
            val isB64 = { c: Char -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '=' }
            val odd = body.filter { !isB64(it) && !it.isWhitespace() && it != '-' }.groupingBy { it }.eachCount().toSortedMap()
            var b64 = body.filter(isB64).trimEnd('=')
            if (b64.length % 4 != 0) b64 += "=".repeat(4 - b64.length % 4)
            return PemPieces(b64, begin?.groupValues?.get(2)?.trim()?.uppercase()?.ifEmpty { null }, begin != null || "BEGIN" in text,
                end != null || "END" in text, odd, text.length)
        }
    }
}
