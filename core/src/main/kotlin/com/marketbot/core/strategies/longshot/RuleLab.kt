package com.marketbot.core.strategies.longshot

import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.Evidence
import com.marketbot.core.strategy.EvidenceLevel
import com.marketbot.core.strategy.Help
import com.marketbot.core.strategy.Param
import com.marketbot.core.strategy.PreflightResult
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.variantName
import java.time.Instant

/**
 * A paper-only strategy defined entirely by its settings, so a new rule can be forward-tested
 * without a new build: Claude proposes one through the sync (which can only ask for paper-safe
 * settings), you approve it, and it runs. The main slot and its variants B and C are three rules
 * side by side.
 *
 * A rule: which Kalshi series; whether to SELL the cheap side (bet against the longshot) or BUY
 * it; MAKER (rest at the near price, fill from the public tape) or TAKER (cross the spread now,
 * pay the fee); which side may be the cheap one; the cheap side's price band; which strikes; the
 * window before close; and how long an unfilled offer may rest.
 */
class RuleLab(id: String = ID, baseId: String? = null) : KalshiPaperRange(id, baseId) {
    override val name = variantName("Rule Lab", id, baseId)
    override val measuredLead = "the market's close"
    override val summary = "Paper test bench: runs whatever rule its settings describe on the Kalshi series you name. Built for testing ideas, not for money."
    override val help = Help(
        makes = "Nothing by design: it measures. A rule earns its keep only if its paper record beats what prices implied, and it passes the graduation gates.",
        loses = "Paper only. A rule that crosses the spread pays Kalshi's taker fee on every order, which kills most ideas; a rule that rests is filled most often when it is wrong.",
        notes = listOf(
            "Direction: sell_cheap bets against the cheap side (what the weather longshot does); buy_cheap buys it.",
            "Execution: maker rests at the near price and fills from Kalshi's public tape; taker fills at once at the far price and pays the fee.",
            "Cheap side: any, yes (only markets whose YES is cheap) or no.",
            "Strikes: any, between (range buckets) or tails (above/below a level).",
            "Claude can propose a new rule through Claude sync; it applies only when you approve it.",
        ),
    )
    override val evidence = Evidence(EvidenceLevel.UNPROVEN, "A rule under test: its record here is the only evidence.", emptyList(), "this app's paper record")
    override val defaultSeries = "KXGOLDD,KXSILVERD,KXWTI,KXBRENTD"
    override val seriesHelp = "Kalshi series the rule trades, comma-separated (for example KXHIGHNY,KXGOLDD)."
    override val defaultMinLeadMin = 660
    override val defaultMaxLeadMin = 780
    override val defaultEveryMin = 15

    override val moreParams: List<Param> get() = listOf(
        Param.Text("direction", "Direction", "sell_cheap (bet against the cheap side) or buy_cheap (buy it).", "sell_cheap"),
        Param.Text("execution", "Execution", "maker (rest at the near price) or taker (cross now, pay the fee).", "maker"),
        Param.Text("cheap_side", "Which side may be cheap", "any, yes or no.", "any"),
        Param.Text("strikes", "Strikes", "any, between (range buckets) or tails (above/below a level).", "any"),
        Param.Int_("rest_max_min", "Withdraw unfilled after", "Minutes an unfilled offer may rest; 0 rests until close.", 0, 0, 2880, "min"),
    )

    override fun validate(config: StrategyConfig): List<String> {
        val out = super.validate(config).toMutableList()
        if (config.text("direction") !in DIRECTIONS) out += "Direction must be one of ${DIRECTIONS.joinToString()}."
        if (config.text("execution") !in EXECUTIONS) out += "Execution must be maker or taker."
        if (config.text("cheap_side") !in SIDES) out += "Which side may be cheap must be any, yes or no."
        if (config.text("strikes") !in STRIKES) out += "Strikes must be any, between or tails."
        if (series(config).isEmpty()) out += "Name at least one Kalshi series."
        return out
    }

    override fun effects(config: StrategyConfig): List<String> = listOf(rule(config)) + super.effects(config)

    /** The rule in one sentence, for the strategy screen and the sync snapshot. */
    fun rule(c: StrategyConfig): String {
        val dir = if (c.text("direction") == "buy_cheap") "Buy" else "Sell"
        val ex = if (c.text("execution") == "taker") "crossing the spread (taker fee)" else "resting at the near price"
        val side = when (c.text("cheap_side")) { "yes" -> "cheap YES"; "no" -> "cheap NO"; else -> "the cheap side" }
        return "$dir $side priced ${(c.num("min_cheap") * 100).toInt()}-${(c.num("max_cheap") * 100).toInt()}c on ${series(c).joinToString(", ")}" +
            (if (c.text("strikes") != "any") " (${c.text("strikes")} strikes)" else "") +
            ", ${c.int("min_lead_min")}-${c.int("max_lead_min")} min before close, $ex" +
            (if (c.int("rest_max_min") > 0 && c.text("execution") != "taker") ", withdrawn unfilled after ${c.int("rest_max_min")} min" else "") + "."
    }

    override fun pick(m: RangeCandidate, c: StrategyConfig): CheapPick? = pickFor(m, c)

    override fun withdrawAt(q: QuoteRow, c: StrategyConfig): Instant? {
        val ref = refOf(q) ?: q.closeTime
        val rest = c.int("rest_max_min")
        if (rest <= 0) return ref
        val cut = q.placedAt.plusSeconds(rest * 60L)
        return if (ref == null || cut.isBefore(ref)) cut else ref
    }

    override fun ownsMarket(venue: String, ticker: String) = false   // paper only: it never has an order at the exchange

    override suspend fun preflight(ctx: StrategyContext): PreflightResult {
        val steps = series(ctx).take(4).map { s ->
            val ms = runCatching { ctx.kalshi.openMarkets(s) }
            "List open $s markets (${ms.getOrNull()?.size ?: "failed: ${ms.exceptionOrNull()?.message}"})" to ms.isSuccess
        }
        val ok = steps.isNotEmpty() && steps.all { it.second }
        return PreflightResult(ok, steps, if (ok) "Ready to paper-trade the rule." else "Check the series names.")
    }

    companion object {
        const val ID = "rule_lab"
        val DIRECTIONS = listOf("sell_cheap", "buy_cheap")
        val EXECUTIONS = listOf("maker", "taker")
        val SIDES = listOf("any", "yes", "no")
        val STRIKES = listOf("any", "between", "tails")

        /**
         * The order a rule places, in the four combinations. YES cheap at ask a / bid b:
         *  sell maker: sell YES at a | sell taker: buy NO at 1 - b | buy maker: buy YES at b | buy taker: buy YES at a.
         * NO cheap (YES expensive): the mirror image on the NO side.
         */
        fun pickFor(m: RangeCandidate, c: StrategyConfig): CheapPick? {
            val bid = m.yesBid; val ask = m.yesAsk
            if (bid == null || ask == null || bid <= 0.0 || ask >= 1.0 || !CheapSide.spreadOk(bid, ask)) return null
            when (c.text("strikes")) {
                "between" -> if (m.strikeType != "between") return null
                "tails" -> if (m.strikeType == null || m.strikeType == "between") return null
            }
            val lo = c.num("min_cheap"); val hi = c.num("max_cheap")
            val taker = c.text("execution") == "taker"
            val sell = c.text("direction") != "buy_cheap"
            val side = c.text("cheap_side")
            val yesCheap = ask < hi && side != "no"
            val noCheap = !yesCheap && 1 - bid < hi && side != "yes"
            return when {
                yesCheap -> when {
                    sell && !taker -> CheapPick("sell_yes", ask, ask).takeIf { ask >= lo }
                    sell && taker -> CheapPick("buy_no", 1 - bid, bid, taker = true).takeIf { bid >= lo }
                    !sell && !taker -> CheapPick("buy_yes", bid, bid).takeIf { bid >= lo }
                    else -> CheapPick("buy_yes", ask, ask, taker = true).takeIf { ask >= lo }
                }
                noCheap -> {
                    val noAsk = 1 - bid; val noBid = 1 - ask
                    when {
                        sell && !taker -> CheapPick("buy_yes", bid, noAsk).takeIf { noAsk >= lo }
                        sell && taker -> CheapPick("buy_yes", ask, noBid, taker = true).takeIf { noBid >= lo }
                        !sell && !taker -> CheapPick("buy_no", noBid, noBid).takeIf { noBid >= lo }
                        else -> CheapPick("buy_no", noAsk, noAsk, taker = true).takeIf { noAsk >= lo }
                    }
                }
                else -> null
            }
        }
    }
}
