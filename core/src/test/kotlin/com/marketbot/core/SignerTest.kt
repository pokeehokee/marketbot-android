package com.marketbot.core

import com.marketbot.core.net.KalshiSigner
import com.marketbot.core.net.PolymarketSigner
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.RSABlindedEngine
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.PSSSigner
import org.bouncycastle.crypto.util.PublicKeyFactory
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignerTest {
    private val sig = golden("signatures").o()

    private fun pssVerify(publicPem: String, msg: String, sigB64: String): Boolean {
        val der = Base64.getMimeDecoder().decode(publicPem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
        val pub = PublicKeyFactory.createKey(SubjectPublicKeyInfo.getInstance(der)) as RSAKeyParameters
        val v = PSSSigner(RSABlindedEngine(), SHA256Digest(), 32)
        v.init(false, pub)
        val b = msg.toByteArray()
        v.update(b, 0, b.size)
        return v.verifySignature(Base64.getDecoder().decode(sigB64))
    }

    @Test fun `Ed25519 signatures equal the official Polymarket SDK byte for byte`() {
        val e = sig["ed25519"].o()
        for (secret in listOf(e["secret_key_b64"].s!!, e["seed_b64"].s!!)) {
            val s = PolymarketSigner("key", secret)
            for (c in e["cases"].list().map { it.o() })
                assertEquals(c["signature"].s, s.sign(c["timestamp"].s + c["method"].s + c["path"].s))
        }
    }

    @Test fun `Polymarket headers sign the path without the query`() {
        val e = sig["ed25519"].o()
        val c = e["cases"].list()[0].o()
        val s = PolymarketSigner("kid", e["seed_b64"].s!!, nowMs = { c["timestamp"].s!!.toLong() })
        val h = s.headers("GET", "https://api.polymarket.us${c["path"].s}?limit=5".toHttpUrl())
        assertEquals("kid", h["X-PM-Access-Key"])
        assertEquals(c["timestamp"].s, h["X-PM-Timestamp"])
        assertEquals(c["signature"].s, h["X-PM-Signature"])
    }

    @Test fun `Kalshi RSA-PSS signatures verify against the public key, from PKCS1 and PKCS8 keys`() {
        val r = sig["rsa_pss"].o()
        for (pem in listOf(r["pkcs1_pem"].s!!, r["pkcs8_pem"].s!!)) {
            val s = KalshiSigner("kid", pem)
            assertTrue(pssVerify(r["public_pem"].s!!, r["preimage"].s!!, s.sign(r["preimage"].s!!)))
        }
        // and the Python `cryptography` signature verifies under the same scheme
        assertTrue(pssVerify(r["public_pem"].s!!, r["preimage"].s!!, r["python_signature"].s!!))
    }

    @Test fun `Kalshi headers sign timestamp + method + full path without query`() {
        val r = sig["rsa_pss"].o()
        val s = KalshiSigner("kid", r["pkcs1_pem"].s!!, nowMs = { 1793134000000 })
        val h = s.headers("get", "https://external-api.kalshi.com/trade-api/v2/portfolio/balance?exchange_index=2".toHttpUrl())
        assertEquals("1793134000000", h["KALSHI-ACCESS-TIMESTAMP"])
        assertTrue(pssVerify(r["public_pem"].s!!, "1793134000000GET/trade-api/v2/portfolio/balance", h["KALSHI-ACCESS-SIGNATURE"]!!))
    }

    @Test fun `bad keys are refused with a readable message`() {
        assertFailsWith<IllegalArgumentException> { KalshiSigner("k", "not a pem") }
        assertFailsWith<IllegalArgumentException> { PolymarketSigner("k", "@@@") }
        assertFailsWith<IllegalArgumentException> { PolymarketSigner("k", Base64.getEncoder().encodeToString(ByteArray(10))) }
    }
}
