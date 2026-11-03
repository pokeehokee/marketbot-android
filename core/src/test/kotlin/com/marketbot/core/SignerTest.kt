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

    @Test fun `a JCA RSA-PSS key (what the Android Keystore runs) signs exactly like the PEM signer`() {
        val r = sig["rsa_pss"].o()
        val der = Base64.getMimeDecoder().decode(r["pkcs8_pem"].s!!.lines().filterNot { it.startsWith("-----") }.joinToString(""))
        val key = java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
        val s = KalshiSigner.external("kid", { bytes ->
            java.security.Signature.getInstance("RSASSA-PSS").run {
                setParameter(java.security.spec.PSSParameterSpec("SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, 32, 1))
                initSign(key); update(bytes); sign()
            }
        }, nowMs = { 1_700_000_000_000 })
        assertTrue(pssVerify(r["public_pem"].s!!, r["preimage"].s!!, s.sign(r["preimage"].s!!)))
        val h = s.headers("GET", "https://api.elections.kalshi.com/trade-api/v2/portfolio/balance?x=1".toHttpUrl())
        assertTrue(pssVerify(r["public_pem"].s!!, "1700000000000GET/trade-api/v2/portfolio/balance", h["KALSHI-ACCESS-SIGNATURE"]!!))
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

    @Test fun `pasted keys a phone has mangled still parse`() {
        val r = sig["rsa_pss"].o()
        for (good in listOf(r["pkcs1_pem"].s!!, r["pkcs8_pem"].s!!)) {
            val body = good.lines().filterNot { it.startsWith("-----") }.joinToString("")
            val head = good.lines().first(); val foot = good.lines().last { it.isNotBlank() }
            for (pem in listOf(
                good.replace("\n", "\r\n"),                 // Windows line ends
                good.replace("\n", " "),                      // newlines became spaces
                "$head$body$foot",                           // all on one line
                "  \n$good\n\n  ",                             // stray whitespace
                body,                                        // no BEGIN/END lines
                good.lines().joinToString("\n") { "  $it" },    // indented
            )) {
                val s = KalshiSigner("kid", pem)
                assertTrue(pssVerify(r["public_pem"].s!!, r["preimage"].s!!, s.sign(r["preimage"].s!!)), pem.take(40))
            }
        }
    }

    @Test fun `wrong keys get a message that says what to do`() {
        val r = sig["rsa_pss"].o()
        val e = assertFailsWith<IllegalArgumentException> { KalshiSigner("kid", r["public_pem"].s!!) }
        assertTrue("public key" in e.message!!, e.message)
        assertTrue("password" in assertFailsWith<IllegalArgumentException> { KalshiSigner("kid", "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----") }.message!!)
        assertTrue("RSA private key" in assertFailsWith<IllegalArgumentException> { KalshiSigner("kid", "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----") }.message!!)
        assertEquals(null, KalshiSigner.keyIdProblem("a952bcbe-ec3b-4b5b-b8f9-11dae589608c"))
        assertTrue("private key" in KalshiSigner.keyIdProblem(r["pkcs1_pem"].s!!)!!)
        assertTrue("spaces" in KalshiSigner.keyIdProblem("abc def")!!)
    }
}
