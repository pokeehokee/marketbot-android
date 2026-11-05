package com.marketbot.core.sync

import com.marketbot.core.store.LedgerCsv
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Param
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.StrategyConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The sync mailbox between the app and Claude: a private GitHub repository.
 *
 * App to Claude: `snapshot/latest.json` (every strategy's mode, settings and
 * record) and `exports/orders-latest.csv` (the ledger, as Settings > Export
 * writes it). No secret is ever in either.
 *
 * Claude to app: `proposals/pending.json`, a list of [Proposal]s. The app shows
 * each one, and nothing applies until the user approves it on the phone. Every
 * proposal goes through [ProposalPolicy] first, so the file can only ever ask for
 * paper-safe changes. Each outcome is written back to `proposals/results.json`.
 */
@Serializable
data class Proposal(
    val id: String,
    val strategy: String,
    /** Settings to change, by key, as the settings screen stores them. */
    val set: Map<String, String> = emptyMap(),
    /** OFF or PAPER. LIVE is never accepted. */
    val mode: String? = null,
    val reason: String = "",
)

@Serializable
data class ProposalFile(val proposals: List<Proposal> = emptyList())

@Serializable
data class ProposalOutcome(val status: String, val at: String, val detail: String = "")

@Serializable
data class StrategySnapshot(
    val id: String, val name: String, val baseId: String? = null, val mode: String, val armed: Boolean,
    val settings: Map<String, String>, val record: String? = null,
)

@Serializable
data class Snapshot(val at: String, val appVersion: String, val strategies: List<StrategySnapshot>)

/** What a proposal may change, decided on the phone whatever the file says. */
object ProposalPolicy {
    /** Settings that put more at risk when raised. */
    private val RISKIER_HIGHER = setOf(
        "lot", "lot_cap", "max_contracts", "daily_loss_limit", "daily_stop", "total_kill", "collateral_fraction",
        "per_instrument_frac", "per_instrument_max_usd", "max_inventory_frac", "max_drawdown_frac",
        "max_open", "max_markets", "max_instruments",
    )
    /** Settings that put more at risk when lowered. */
    private val RISKIER_LOWER = setOf("hard_floor_usd", "cash_reserve_frac", "min_venues_live")

    sealed interface Verdict {
        /** [needsStrongConfirm]: the change turns a strategy on or off, so it takes a fingerprint, not just a tap. */
        data class Ok(val changes: Map<String, String>, val mode: Mode?, val needsStrongConfirm: Boolean) : Verdict
        data class Refused(val why: String) : Verdict
    }

    fun check(p: Proposal, s: Strategy?, current: StrategyConfig?, currentMode: Mode): Verdict {
        if (s == null || current == null) return Verdict.Refused("no strategy ${p.strategy}")
        if (currentMode == Mode.LIVE) return Verdict.Refused("${s.name} is live; proposals may only change paper strategies")
        val mode = p.mode?.let { m -> runCatching { Mode.valueOf(m.uppercase()) }.getOrNull() ?: return Verdict.Refused("unknown mode $m") }
        if (mode == Mode.LIVE) return Verdict.Refused("a proposal can never switch anything to live")
        if (mode != null && mode !in s.supportedModes) return Verdict.Refused("${s.name} does not support $mode")
        if (p.set.isEmpty() && mode == null) return Verdict.Refused("nothing to change")
        val out = LinkedHashMap<String, String>()
        for ((k, v) in p.set) {
            val prm = s.params.firstOrNull { it.key == k } ?: return Verdict.Refused("${s.name} has no setting $k")
            val value = valid(prm, v) ?: return Verdict.Refused("$k = $v is outside what the setting allows")
            val old = current.raw(k).toDoubleOrNull(); val new = value.toDoubleOrNull()
            if (old != null && new != null) {
                if (k in RISKIER_HIGHER && new > old + 1e-12) return Verdict.Refused("raising $k puts more at risk; change it on the phone if you mean it")
                if (k in RISKIER_LOWER && new < old - 1e-12) return Verdict.Refused("lowering $k puts more at risk; change it on the phone if you mean it")
            }
            out[k] = value
        }
        return Verdict.Ok(out, mode, needsStrongConfirm = mode != null && mode != currentMode)
    }

    /** The value normalised, or null if the setting would not accept it. */
    private fun valid(p: Param, v: String): String? = when (p) {
        is Param.Num -> v.trim().toDoubleOrNull()?.takeIf { it in p.min..p.max }?.toString()
        is Param.Int_ -> v.trim().toDoubleOrNull()?.takeIf { it == Math.rint(it) && it.toInt() in p.min..p.max }?.toInt()?.toString()
        is Param.Bool -> v.trim().lowercase().takeIf { it == "true" || it == "false" }
        is Param.Text -> v.takeIf { it.length <= 500 }
    }
}

/**
 * The GitHub contents API for one repository, with a token limited to it. Files
 * are read and written whole; a write sends the file's current sha, as the API
 * requires for an update.
 */
class GitHubMailbox(
    private val repo: String,
    private val token: String,
    private val base: String = "https://api.github.com",
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build(),
) {
    init { require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo)) { "Repository must look like owner/name." } }

    private val json = Json { ignoreUnknownKeys = true }

    private fun req(path: String) = Request.Builder().url("$base/repos/$repo/contents/$path")
        .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    /** (text, sha), or null when the file does not exist. */
    fun read(path: String): Pair<String, String>? {
        http.newCall(req(path).get().build()).execute().use { r ->
            if (r.code == 404) return null
            check(r.isSuccessful) { "GitHub ${r.code} reading $path" + if (r.code == 401 || r.code == 403) ": check the token can read and write this repository" else "" }
            val o = json.parseToJsonElement(r.body!!.string()) as kotlinx.serialization.json.JsonObject
            val content = (o["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val sha = (o["sha"] as kotlinx.serialization.json.JsonPrimitive).content
            return String(Base64.getMimeDecoder().decode(content)) to sha
        }
    }

    fun write(path: String, text: String, message: String) {
        val sha = read(path)?.second
        val body = kotlinx.serialization.json.buildJsonObject {
            put("message", kotlinx.serialization.json.JsonPrimitive(message))
            put("content", kotlinx.serialization.json.JsonPrimitive(Base64.getEncoder().encodeToString(text.toByteArray())))
            sha?.let { put("sha", kotlinx.serialization.json.JsonPrimitive(it)) }
        }.toString()
        http.newCall(req(path).put(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            check(r.isSuccessful) { "GitHub ${r.code} writing $path" + if (r.code == 401 || r.code == 403) ": check the token can read and write this repository" else "" }
        }
    }

    fun proposals(): List<Proposal> = read(PENDING)?.first?.let { json.decodeFromString(ProposalFile.serializer(), it).proposals } ?: emptyList()

    fun recordOutcomes(outcomes: Map<String, ProposalOutcome>) {
        if (outcomes.isEmpty()) return
        val ser = kotlinx.serialization.builtins.MapSerializer(String.serializer(), ProposalOutcome.serializer())
        val old = read(RESULTS)?.first?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyMap()
        write(RESULTS, Json { prettyPrint = true }.encodeToString(ser, old + outcomes), "app: ${outcomes.size} proposal outcome(s)")
    }

    companion object {
        const val SNAPSHOT = "snapshot/latest.json"
        const val ORDERS = "exports/orders-latest.csv"
        const val PENDING = "proposals/pending.json"
        const val RESULTS = "proposals/results.json"
    }
}

/** Builds what the app sends: no secrets, only settings, modes, records and the ledger. */
object SyncPayload {
    private val pretty = Json { prettyPrint = true }

    fun snapshot(at: Instant, appVersion: String, entries: List<StrategySnapshot>) =
        pretty.encodeToString(Snapshot.serializer(), Snapshot(at.toString(), appVersion, entries))

    fun ordersCsv(rows: Sequence<com.marketbot.core.store.QuoteRow>) = buildString {
        append(LedgerCsv.header()); rows.forEach { append(LedgerCsv.row(it)) }
    }
}
