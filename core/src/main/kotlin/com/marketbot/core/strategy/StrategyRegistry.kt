package com.marketbot.core.strategy

import com.marketbot.core.strategies.deepitm.KalshiBtcDeepItm
import com.marketbot.core.strategies.deepitm.PolymarketBtcDeepItm
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
    )

    fun byId(id: String): Strategy? = all.firstOrNull { it.id == id }
}
