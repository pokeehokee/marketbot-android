package com.marketbot.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.marketbot.core.engine.Credentials
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and decrypts short secrets. */
interface Crypter {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore and never leaves
 * it. The ciphertext (IV + data) is what reaches app storage; uninstalling
 * the app destroys the key, and with it every saved secret.
 */
class KeystoreCrypter(private val alias: String = "marketbot-credentials") : Crypter {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

/** API keys, encrypted at rest. Nothing here is ever logged or shown back in full. */
class Vault(private val prefs: SharedPreferences, private val crypter: Crypter) {
    constructor(ctx: Context) : this(ctx.getSharedPreferences("vault", Context.MODE_PRIVATE), KeystoreCrypter())

    private fun put(k: String, v: String?) {
        prefs.edit().apply {
            if (v.isNullOrBlank()) remove(k) else putString(k, Base64.getEncoder().encodeToString(crypter.encrypt(v.trim().toByteArray())))
        }.apply()
    }

    private fun get(k: String): String? = prefs.getString(k, null)?.let {
        runCatching { String(crypter.decrypt(Base64.getDecoder().decode(it))) }.getOrNull()
    }

    fun credentials() = Credentials(get("kalshi_key_id"), get("kalshi_pem"), get("pm_key_id"), get("pm_secret"),
        kalshiDemo = kalshiDemo(), kalshiSubaccount = kalshiSubaccount())

    /** Kalshi's demo exchange (fake money) instead of the real one. Not a secret, so stored plainly. */
    fun kalshiDemo() = prefs.getBoolean("kalshi_demo", false)
    fun setKalshiDemo(on: Boolean) = prefs.edit().putBoolean("kalshi_demo", on).apply()

    /** 0 = the primary account; 1+ a Kalshi subaccount that keeps the bot's money apart. */
    fun kalshiSubaccount() = prefs.getInt("kalshi_subaccount", 0)
    fun setKalshiSubaccount(n: Int) { require(n in 0..32) { "subaccount must be 0-32" }; prefs.edit().putInt("kalshi_subaccount", n).apply() }

    fun saveKalshi(keyId: String?, pem: String?) { put("kalshi_key_id", keyId); put("kalshi_pem", pem) }
    fun savePolymarket(keyId: String?, secret: String?) { put("pm_key_id", keyId); put("pm_secret", secret) }

    /** For display: the last four characters of the key id, never the secret. */
    fun kalshiHint(): String? = get("kalshi_key_id")?.takeLast(4)
    fun polymarketHint(): String? = get("pm_key_id")?.takeLast(4)
}
