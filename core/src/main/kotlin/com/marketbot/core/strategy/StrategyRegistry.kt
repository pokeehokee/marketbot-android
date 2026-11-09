package com.marketbot.core.strategy

import com.marketbot.core.strategies.bets.ResearchBets
import com.marketbot.core.strategies.deepitm.KalshiBtcDeepItm
import com.marketbot.core.strategies.deepitm.PolymarketBtcDeepItm
import com.marketbot.core.strategies.longshot.KalshiCommodityLongshot
import com.marketbot.core.strategies.longshot.PolymarketTemperatureLongshot
import com.marketbot.core.strategies.longshot.RuleLab
import com.marketbot.core.strategies.longshot.WeatherLongshot
import com.marketbot.core.strategies.rewards.RewardFarm

/**
 * Every method the app can run. To add one: implement [Strategy] (its
 * settings, go-live checklist, preflight and stats come with it) and add it
 * here. Nothing else in the app needs to change.
 */
object StrategyRegistry {
    val all: List<Strategy> = listOf(
        WeatherLongshot(),
        KalshiBtcDeepItm(),
        PolymarketBtcDeepItm(),
        RewardFarm(),
        PolymarketTemperatureLongshot(),
        KalshiCommodityLongshot(),
        RuleLab(),
        ResearchBets(),
    ) + VARIANTS.flatMap { v -> listOf(
        WeatherLongshot(variantId(WeatherLongshot.ID, v), WeatherLongshot.ID),
        KalshiBtcDeepItm(variantId(KalshiBtcDeepItm.ID, v), KalshiBtcDeepItm.ID),
        PolymarketBtcDeepItm(variantId(PolymarketBtcDeepItm.ID, v), PolymarketBtcDeepItm.ID),
        RewardFarm(variantId(RewardFarm.ID, v), RewardFarm.ID),
        PolymarketTemperatureLongshot(variantId(PolymarketTemperatureLongshot.ID, v), PolymarketTemperatureLongshot.ID),
        KalshiCommodityLongshot(variantId(KalshiCommodityLongshot.ID, v), KalshiCommodityLongshot.ID),
        RuleLab(variantId(RuleLab.ID, v), RuleLab.ID),
    ) }

    fun byId(id: String): Strategy? = all.firstOrNull { it.id == id }

    /** The paper variants of [baseId], in order. */
    fun variantsOf(baseId: String): List<Strategy> = all.filter { it.baseId == baseId }
}

/** Paper variants per strategy (octagon runs settings side by side in paper the same way). Two is enough to compare. */
val VARIANTS = listOf("b", "c")

fun variantId(baseId: String, v: String) = "$baseId@$v"

fun variantName(name: String, id: String, baseId: String?) =
    if (baseId == null) name else "$name · variant ${id.substringAfterLast('@').uppercase()}"
