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
        assertTrue("Kalshi private key" in assertFailsWith<IllegalArgumentException> { KalshiSigner("kid", "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----") }.message!!)
        assertEquals(null, KalshiSigner.keyIdProblem("a952bcbe-ec3b-4b5b-b8f9-11dae589608c"))
        assertTrue("private key" in KalshiSigner.keyIdProblem(r["pkcs1_pem"].s!!)!!)
        assertTrue("spaces" in KalshiSigner.keyIdProblem("abc def")!!)
    }

    @Test fun `marker lines with spaces, long dashes, no dashes, or a wrong label still parse`() {
        val r = sig["rsa_pss"].o()
        val good = r["pkcs1_pem"].s!!
        val body = good.lines().filterNot { it.startsWith("-----") }.joinToString("\n")
        val p8body = r["pkcs8_pem"].s!!.lines().filterNot { it.startsWith("-----") }.joinToString("\n")
        for (pem in listOf(
            "----- BEGIN RSA PRIVATE KEY -----\n$body\n----- END RSA PRIVATE KEY -----",
            "\u2014\u2014\u2014\u2014\u2014BEGIN RSA PRIVATE KEY\u2014\u2014\u2014\u2014\u2014\n$body\n\u2014\u2014\u2014\u2014\u2014END RSA PRIVATE KEY\u2014\u2014\u2014\u2014\u2014",
            "\u2013\u2013BEGIN RSA PRIVATE KEY\u2013\u2013 $body \u2013\u2013END RSA PRIVATE KEY\u2013\u2013",
            "BEGIN RSA PRIVATE KEY\n$body\nEND RSA PRIVATE KEY",
            "-----BEGIN RSA PRIVATE KEY-----\n${body.trimEnd('=', '\n')}\n-----END RSA PRIVATE KEY-----",   // padding lost
            "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----",                          // PKCS#1 under a PKCS#8 label
            "-----BEGIN RSA PRIVATE KEY-----\n$p8body\n-----END RSA PRIVATE KEY-----",                // and the reverse
            "\uFEFF$good\u200B",                                                                    // BOM and zero-width space
        )) {
            val s = KalshiSigner("kid", pem)
            assertTrue(pssVerify(r["public_pem"].s!!, r["preimage"].s!!, s.sign(r["preimage"].s!!)), pem.take(60))
        }
    }

    @Test fun `a damaged key says what was read without printing any of it`() {
        val good = sig["rsa_pss"].o()["pkcs1_pem"].s!!
        val lines = good.lines()
        val cut = (lines.take(5) + lines.last { it.isNotBlank() }).joinToString("\n")   // most of the key missing
        val e = assertFailsWith<IllegalArgumentException> { KalshiSigner("kid", cut) }.message!!
        assertTrue("bytes" in e && "BEGIN line \"RSA PRIVATE KEY\"" in e && "Import PEM file" in e, e)
        assertTrue(lines[2] !in e && lines[2].take(12) !in e, "no key text in the message")
    }

    // ---------------------------------------------------------------- Ed25519 (Kalshi's default key type)

    private fun ed25519Pem(): Pair<String, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters> {
        val gen = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator()
        gen.init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(java.security.SecureRandom()))
        val kp = gen.generateKeyPair()
        // Exactly what Kalshi (and `openssl genpkey -algorithm ed25519`) writes: RFC 8410 PKCS#8 v1, seed only.
        val prefix = byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20)
        val der = prefix + (kp.private as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters).encoded
        val b64 = Base64.getEncoder().encodeToString(der)
        return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n" to (kp.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters)
    }

    private fun edVerify(pub: org.bouncycastle.crypto.params.Ed25519PublicKeyParameters, msg: String, sigB64: String): Boolean {
        val v = org.bouncycastle.crypto.signers.Ed25519Signer(); v.init(false, pub)
        val m = msg.toByteArray(); v.update(m, 0, m.size)
        return v.verifySignature(Base64.getDecoder().decode(sigB64))
    }

    @Test fun `Kalshi Ed25519 keys (the PKCS#8 PEM Kalshi now generates) sign the request text directly`() {
        val (pem, pub) = ed25519Pem()
        assertTrue(pem.lines()[1].startsWith("MC4CAQAwBQYDK2Vw"), "the 48-byte PKCS#8 form Kalshi hands out")
        assertEquals("Ed25519", KalshiSigner.keyType(pem))
        val s = KalshiSigner("kid", pem, nowMs = { 1_793_134_000_000 })
        val h = s.headers("get", "https://api.elections.kalshi.com/trade-api/v2/portfolio/balance?subaccount=1".toHttpUrl())
        assertEquals("kid", h["KALSHI-ACCESS-KEY"]); assertEquals("1793134000000", h["KALSHI-ACCESS-TIMESTAMP"])
        assertEquals(64, Base64.getDecoder().decode(h["KALSHI-ACCESS-SIGNATURE"]).size)
        assertTrue(edVerify(pub, "1793134000000GET/trade-api/v2/portfolio/balance", h["KALSHI-ACCESS-SIGNATURE"]!!))
        // Pasted badly, it still parses.
        val body = pem.lines()[1]
        for (p in listOf("----- BEGIN PRIVATE KEY ----- $body ----- END PRIVATE KEY -----", body, pem.replace("\n", "\r\n")))
            assertTrue(edVerify(pub, "x", KalshiSigner("kid", p).sign("x")), p.take(30))
        assertEquals("RSA", KalshiSigner.keyType(sig["rsa_pss"].o()["pkcs1_pem"].s!!))
        // The PKCS#8 v2 form (seed plus public key), as some libraries write it, parses too.
        val gen = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator()
        gen.init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(java.security.SecureRandom()))
        val kp = gen.generateKeyPair()
        val v2 = Base64.getEncoder().encodeToString(org.bouncycastle.crypto.util.PrivateKeyInfoFactory.createPrivateKeyInfo(kp.private).encoded)
        assertTrue(edVerify(kp.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters, "y",
            KalshiSigner("kid", "-----BEGIN PRIVATE KEY-----\n$v2\n-----END PRIVATE KEY-----").sign("y")))
        // The Keystore holds RSA only, so an Ed25519 key is refused there (and kept encrypted instead).
        assertTrue("Ed25519" in assertFailsWith<IllegalArgumentException> { KalshiSigner.parseRsaPrivateKey(pem) }.message!!)
    }
}
