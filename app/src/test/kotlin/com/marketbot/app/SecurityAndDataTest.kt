package com.marketbot.app

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.marketbot.app.data.Backup
import com.marketbot.app.data.Crypter
import com.marketbot.app.data.HardwareKey
import com.marketbot.app.data.MarketbotDb
import com.marketbot.app.data.QuoteEntity
import com.marketbot.app.data.StateEntity
import com.marketbot.app.data.Vault
import com.marketbot.core.net.KalshiSigner
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SecurityAndDataTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = MarketbotDb.inMemory(ctx)
    @After fun close() = db.close()

    // ---------------------------------------------------------------- backup
    @Test fun `a sealed backup opens only with its password`() {
        val blob = Backup.seal("hello".toByteArray(), "correct horse".toCharArray(), iterations = 10_000)
        assertContentEquals("hello".toByteArray(), Backup.open(blob, "correct horse".toCharArray()))
        assertFailsWith<Backup.WrongPassword> { Backup.open(blob, "wrong horse!".toCharArray()) }
        assertFailsWith<Backup.WrongPassword> { Backup.open("not a backup".toByteArray(), "correct horse".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { Backup.seal(byteArrayOf(), "short".toCharArray()) }
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<Backup.WrongPassword> { Backup.open(tampered, "correct horse".toCharArray()) }
    }

    @Test fun `restore brings back the ledger but never live arming`() = runTest {
        val q = QuoteEntity(0, "s", "LIVE", "kalshi", "T", "sell_yes", 0.07, 5.0, 1, null, "o1", null, null, null, 5.0, "x", 2, null, 3, "no", 0.35,
            "KALSHI_API", "settlements", false, "{}")
        db.quotes().insert(q)
        db.state().put(StateEntity("strategy.s.mode", "LIVE"))
        db.state().put(StateEntity("strategy.s.armed", "{}"))
        db.state().put(StateEntity("strategy.s.config", "{\"a\":\"1\"}"))
        db.state().put(StateEntity("global.kill", "true"))
        val blob = Backup.export(db, "test", "a long password".toCharArray(), iterations = 10_000)
        assertFalse(String(blob).contains("sell_yes"), "the file is not readable without the password")

        val other = MarketbotDb.inMemory(ctx)
        try {
            other.quotes().insert(q.copy(ticker = "LOCAL"))
            Backup.restore(other, blob, "a long password".toCharArray())
            assertEquals(listOf("T"), other.backup().quotes().map { it.ticker }, "replaced, not merged")
            assertEquals(0.35, other.backup().quotes().single().pnl)
            assertEquals("PAPER", other.state().get("strategy.s.mode"))
            assertNull(other.state().get("strategy.s.armed"))
            assertNull(other.state().get("global.kill"))
            assertEquals("{\"a\":\"1\"}", other.state().get("strategy.s.config"))
        } finally { other.close() }
    }

    // ---------------------------------------------------------------- hardware key
    private fun pkcs8Pem(k: PrivateKey) = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----"

    /** Stands in for the Keystore: holds a JCA key and signs with RSA-PSS as the Keystore does. */
    private class FakeHw(val fail: Boolean = false) : HardwareKey {
        var key: PrivateKey? = null
        override fun import(pem: String) {
            if (fail) error("no keystore")
            val der = Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
            key = java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
        }
        override fun signBytes(): ((ByteArray) -> ByteArray)? = key?.let { k -> { b: ByteArray ->
            Signature.getInstance("RSASSA-PSS").run { setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)); initSign(k); update(b); sign() } } }
        override fun delete() { key = null }
    }

    private val plain = object : Crypter { override fun encrypt(plain: ByteArray) = plain; override fun decrypt(blob: ByteArray) = blob }

    @Test fun `the Kalshi key goes into hardware and the PEM is not kept`() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val prefs = ctx.getSharedPreferences("hw1", Context.MODE_PRIVATE)
        val hw = FakeHw()
        val v = Vault(prefs, plain, hw)
        v.saveKalshi("kid", pkcs8Pem(kp.private))
        assertTrue(v.kalshiInHardware())
        assertFalse(prefs.all.values.any { it.toString().contains("PRIVATE KEY") }, "no PEM in app storage")
        val c = v.credentials()
        assertNull(c.kalshiPem); assertNotNull(c.kalshiSignBytes)
        val sig = KalshiSigner.external("kid", c.kalshiSignBytes!!).sign("msg")
        val ok = Signature.getInstance("RSASSA-PSS").run { setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
            initVerify(kp.public); update("msg".toByteArray()); verify(Base64.getDecoder().decode(sig)) }
        assertTrue(ok)
        v.saveKalshi(null, null)
        assertNull(hw.key); assertFalse(v.kalshiInHardware())
    }

    @Test fun `without a keystore the key is kept encrypted instead`() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val v = Vault(ctx.getSharedPreferences("hw2", Context.MODE_PRIVATE), plain, FakeHw(fail = true))
        v.saveKalshi("kid", pkcs8Pem(kp.private))
        assertFalse(v.kalshiInHardware())
        assertNotNull(v.credentials().kalshiPem); assertNull(v.credentials().kalshiSignBytes)
    }

    // ---------------------------------------------------------------- updates
    @Test fun `update check picks the newest build tag and its apk`() {
        val r = Updates.newest("""[
            {"tag_name":"build-104","draft":false,"html_url":"p104","assets":[{"browser_download_url":"x/marketbot-104.apk"}]},
            {"tag_name":"build-110","draft":true,"html_url":"p110","assets":[]},
            {"tag_name":"v2","draft":false,"html_url":"pv2","assets":[]},
            {"tag_name":"build-107","draft":false,"html_url":"p107","assets":[{"browser_download_url":"x/notes.txt"},{"browser_download_url":"x/marketbot-107.apk"}]}]""")!!
        assertEquals(107, r.build); assertEquals("x/marketbot-107.apk", r.apkUrl)
        assertNull(Updates.newest("[]"))
    }

    // ---------------------------------------------------------------- schema
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MarketbotDb::class.java)

    @Test fun `the committed schema matches the database the app builds`() {
        val version = MarketbotDb.VERSION
        val files = java.io.File("schemas/com.marketbot.app.data.MarketbotDb").listFiles()!!.map { it.nameWithoutExtension.toInt() }
        assertEquals(version, files.max(), "bump the version, commit the new schema json and add a migration")
        helper.createDatabase("mig.db", version).close()
        helper.runMigrationsAndValidate("mig.db", version, true)
    }
}
