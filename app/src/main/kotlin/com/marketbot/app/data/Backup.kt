package com.marketbot.app.data

import androidx.room.withTransaction
import com.marketbot.core.util.JSON
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A backup of the ledger, settings and history, sealed with a password the user
 * chooses (PBKDF2-SHA256, AES-256-GCM). API keys are never in it: they live in
 * the Keystore and must be entered again after a restore. Live arming is not
 * restored either; every strategy comes back in paper.
 */
object Backup {
    private val MAGIC = "MBK1".toByteArray()
    const val ITERATIONS = 210_000

    @Serializable
    data class Contents(
        val format: Int = 1, val createdAt: Long, val appVersion: String,
        val quotes: List<QuoteEntity>, val events: List<EventEntity>, val state: List<StateEntity>,
        val equity: List<EquityEntity>, val audit: List<AuditEntity>,
    )

    class WrongPassword : Exception("Wrong password, or the file is not a Marketbot backup.")

    private fun key(password: CharArray, salt: ByteArray, iterations: Int) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, iterations, 256)).encoded, "AES")

    fun seal(plain: ByteArray, password: CharArray, iterations: Int = ITERATIONS): ByteArray {
        require(password.size >= 8) { "Use a password of at least 8 characters." }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        val it = java.nio.ByteBuffer.allocate(4).putInt(iterations).array()
        return MAGIC + it + salt + iv + c.doFinal(plain)
    }

    fun open(blob: ByteArray, password: CharArray): ByteArray {
        if (blob.size < 4 + 4 + 16 + 12 + 16 || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) throw WrongPassword()
        val iterations = java.nio.ByteBuffer.wrap(blob, 4, 4).int
        if (iterations !in 10_000..10_000_000) throw WrongPassword()
        val salt = blob.copyOfRange(8, 24)
        val iv = blob.copyOfRange(24, 36)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        return try { c.doFinal(blob, 36, blob.size - 36) } catch (e: javax.crypto.AEADBadTagException) { throw WrongPassword() }
    }

    suspend fun export(db: MarketbotDb, appVersion: String, password: CharArray, iterations: Int = ITERATIONS): ByteArray {
        val d = db.backup()
        val c = Contents(createdAt = System.currentTimeMillis(), appVersion = appVersion, quotes = d.quotes(), events = d.keyEvents(),
            state = d.state(), equity = d.equity(), audit = d.audit())
        return seal(JSON.encodeToString(Contents.serializer(), c).toByteArray(), password, iterations)
    }

    /** State that must not come back from a file: live arming, the live modes it permitted, and a kill switch's memory. */
    fun sanitise(state: List<StateEntity>): List<StateEntity> = state.filterNot { it.key.endsWith(".armed") || it.key.startsWith("global.kill") }
        .map { if (it.key.endsWith(".mode") && it.value == "LIVE") it.copy(value = "PAPER") else it }

    /** Replace everything in the database with the backup. The engine must be stopped. */
    suspend fun restore(db: MarketbotDb, blob: ByteArray, password: CharArray): Contents {
        val c = JSON.decodeFromString(Contents.serializer(), String(open(blob, password)))
        require(c.format == 1) { "This backup was made by a newer version of Marketbot." }
        val d = db.backup()
        db.withTransaction {
            d.clearQuotes(); d.clearEvents(); d.clearState(); d.clearEquity(); d.clearAudit()
            d.putQuotes(c.quotes); d.putEvents(c.events); d.putState(sanitise(c.state)); d.putEquity(c.equity); d.putAudit(c.audit)
        }
        return c
    }
}
