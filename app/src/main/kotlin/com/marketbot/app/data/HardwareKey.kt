package com.marketbot.app.data

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import com.marketbot.core.net.KalshiSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date

/** A signing key held outside app storage. */
interface HardwareKey {
    /** Import the key; throws if this device cannot hold it. */
    fun import(pem: String)
    /** RSA-PSS SHA-256 (32-byte salt) with the held key, or null when there is none. */
    fun signBytes(): ((ByteArray) -> ByteArray)?
    fun delete()
}

/**
 * The Kalshi RSA key, imported into the Android Keystore for signing only. After
 * import the key cannot be read back out, even by this app; a rooted phone or a
 * stolen backup yields nothing. The Keystore needs a certificate beside a private
 * key, so a self-signed one is made for it; nobody else ever sees it.
 */
class KeystoreKalshiKey(private val alias: String = "marketbot-kalshi-rsa") : HardwareKey {
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun import(pem: String) {
        val p = KalshiSigner.parseRsaPrivateKey(pem)
        val kf = KeyFactory.getInstance("RSA")
        val priv = kf.generatePrivate(RSAPrivateCrtKeySpec(p.modulus, p.publicExponent, p.exponent, p.p, p.q, p.dp, p.dq, p.qInv))
        val pub = kf.generatePublic(RSAPublicKeySpec(p.modulus, p.publicExponent))
        val name = X500Name("CN=marketbot kalshi key")
        val cert = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(), Date(System.currentTimeMillis() + 100L * 365 * 86_400_000), name, pub)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(priv)))
        ks().setEntry(alias, KeyStore.PrivateKeyEntry(priv, arrayOf(cert)),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .build())
        // Prove it signs before the PEM is thrown away.
        checkNotNull(signBytes()).invoke(byteArrayOf(1, 2, 3))
    }

    override fun signBytes(): ((ByteArray) -> ByteArray)? {
        val key = runCatching { ks().getKey(alias, null) as? PrivateKey }.getOrNull() ?: return null
        // Android Keystore's PSS uses MGF1 with the same digest and a salt the digest's length: Kalshi's scheme.
        return { bytes -> Signature.getInstance("SHA256withRSA/PSS").run { initSign(key); update(bytes); sign() } }
    }

    override fun delete() { runCatching { ks().deleteEntry(alias) } }
}
