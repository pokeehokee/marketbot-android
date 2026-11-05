package com.marketbot.core.net

import okhttp3.HttpUrl
import org.bouncycastle.asn1.ASN1OctetString
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
 * Kalshi's request signature over `timestamp_ms + METHOD + path` (path without the
 * query string), base64. Kalshi issues two key types and picks the verifier from the
 * registered public key (docs.kalshi.com/getting_started/api_keys, read 2026-11-05):
 * - **Ed25519** (Kalshi's default since 2026): RFC 8032 over the text itself;
 *   the private key comes as PKCS#8 `BEGIN PRIVATE KEY`.
 * - **RSA**: RSA-PSS, SHA-256, MGF1 SHA-256, salt = digest length; usually
 *   PKCS#1 `BEGIN RSA PRIVATE KEY`. Matches weatherbot's `KalshiAuth`.
 * The PEM label does not say which, so the key's own algorithm id decides.
 */
class KalshiSigner private constructor(
    private val keyId: String,
    private val signBytes: (ByteArray) -> ByteArray,
    private val nowMs: () -> Long,
) : RequestSigner {
    constructor(keyId: String, pem: String, nowMs: () -> Long = { System.currentTimeMillis() }) : this(keyId, signerFor(parseKey(pem)), nowMs)

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

        // RFC 8410 algorithm ids.
        private const val OID_ED25519 = "1.3.101.112"
        private const val OID_ED448 = "1.3.101.113"
        private const val OID_X25519 = "1.3.101.110"

        /** A parsed Kalshi private key: which algorithm it signs with decides the signature. */
        sealed interface Key {
            data class Rsa(val key: RSAPrivateCrtKeyParameters) : Key
            data class Ed25519(val key: Ed25519PrivateKeyParameters) : Key
        }

        private fun signerFor(k: Key): (ByteArray) -> ByteArray = when (k) {
            is Key.Rsa -> pssSigner(k.key)
            is Key.Ed25519 -> { bytes -> Ed25519Signer().run { init(true, k.key); update(bytes, 0, bytes.size); generateSignature() } }
        }

        /** "Ed25519" or "RSA" for a key that parses; for display only. */
        fun keyType(pem: String): String = when (parseKey(pem)) { is Key.Rsa -> "RSA"; is Key.Ed25519 -> "Ed25519" }

        private fun pssSigner(key: RSAPrivateCrtKeyParameters): (ByteArray) -> ByteArray {
            val random = SecureRandom()
            return { bytes ->
                val signer = PSSSigner(RSABlindedEngine(), SHA256Digest(), 32)
                signer.init(true, ParametersWithRandom(key, random))
                signer.update(bytes, 0, bytes.size)
                signer.generateSignature()
            }
        }

        /** The RSA key only (the Android Keystore import holds RSA); an Ed25519 key is refused with a reason. */
        fun parseRsaPrivateKey(pem: String): RSAPrivateCrtKeyParameters = when (val k = parseKey(pem)) {
            is Key.Rsa -> k.key
            is Key.Ed25519 -> throw IllegalArgumentException("This is an Ed25519 key, not RSA.")
        }

        /**
         * Any Kalshi private key: Ed25519 PKCS#8, or RSA as PKCS#1 or PKCS#8, from a
         * pasted PEM in whatever state the phone left it ([PemPieces]).
         */
        fun parseKey(pem: String): Key {
            val text = pem.trim()
            require(text.isNotEmpty()) { "Paste the private key, or import the key file Kalshi gave you." }
            require(!text.contains("ENCRYPTED", ignoreCase = true)) { "The key is password-protected; export it without a password." }
            require(!text.contains("PUBLIC KEY", ignoreCase = true)) { "That is a public key. Kalshi needs the private key (the file it gave you when you created the API key)." }
            val p = PemPieces.of(text)
            val der = runCatching { Base64.getDecoder().decode(p.base64) }.getOrElse {
                throw IllegalArgumentException("This does not look like a PEM private key: the key text is not base64. ${p.describe(null)}")
            }
            runCatching { PrivateKeyInfo.getInstance(der) }.getOrNull()?.let { pki ->
                val alg = pki.privateKeyAlgorithm.algorithm.id
                if (alg == OID_ED25519) {
                    val seed = ASN1OctetString.getInstance(pki.parsePrivateKey()).octets
                    require(seed.size == 32) { "This Ed25519 key is ${seed.size} bytes, not 32. ${p.describe(der.size)}" }
                    return Key.Ed25519(Ed25519PrivateKeyParameters(seed, 0))
                }
                require(alg != OID_ED448 && alg != OID_X25519) {
                    "This is a ${if (alg == OID_ED448) "Ed448" else "X25519"} key; Kalshi uses Ed25519 or RSA."
                }
                runCatching { RSAPrivateKey.getInstance(pki.parsePrivateKey()) }.getOrNull()?.let { return rsa(it) }
            }
            // PKCS#1, or PKCS#8 under the wrong label.
            val rsa = runCatching { RSAPrivateKey.getInstance(der) }.getOrElse {
                throw IllegalArgumentException("This does not look like a Kalshi private key (Ed25519 or RSA). ${p.describe(der.size)} " +
                    "Try the Import PEM file button with the key file Kalshi downloaded: pasting can damage a key.")
            }
            return rsa(rsa)
        }

        private fun rsa(k: RSAPrivateKey) = Key.Rsa(RSAPrivateCrtKeyParameters(k.modulus, k.publicExponent, k.privateExponent,
            k.prime1, k.prime2, k.exponent1, k.exponent2, k.coefficient))

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
        bytes?.let { append(" = ").append(it).append(" bytes, where a complete Ed25519 key is 48 and a 2048-bit RSA key about 1,190") }
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
