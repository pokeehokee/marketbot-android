package com.marketbot.core.strategy

import com.marketbot.core.kalshi.KalshiClient
import com.marketbot.core.util.num
import com.marketbot.core.util.str

/**
 * The cross-host guard (port of weatherbot `longshot_reconcile --check`).
 *
 * Before live quoting, every resting order and held position the EXCHANGE
 * reports in this strategy's markets must be one the app's ledger knows. A
 * second trader on the same account (the old desktop, a VM, a restored
 * backup) would otherwise quote the same markets again: the file lock on the
 * desktop only ever covered one machine.
 */
data class ReconcileResult(val foreignOrders: List<String>, val foreignPositions: List<String>, val error: String?) {
    val clean: Boolean get() = error == null && foreignOrders.isEmpty() && foreignPositions.isEmpty()
    val message: String get() = when {
        error != null -> "Could not read the account to check for another trader: $error"
        clean -> "No unknown orders or positions on the account."
        else -> "Unknown to this app: ${foreignOrders.size} resting order(s) ${foreignOrders.take(5)} and " +
            "${foreignPositions.size} position(s) ${foreignPositions.take(5)}. Another bot may be trading this account."
    }
}

suspend fun reconcileKalshi(
    client: KalshiClient,
    inScope: (ticker: String) -> Boolean,
    knownOrderIds: Set<String>,
    knownTickers: Set<String>,
): ReconcileResult = try {
    val orders = client.orders("resting").filter { inScope(it.str("ticker") ?: "") }
    val foreignOrders = orders.filter { (it.str("order_id") ?: "") !in knownOrderIds }.mapNotNull { it.str("ticker") }
    val positions = client.positions().filter {
        val t = it.str("ticker") ?: ""
        inScope(t) && kotlin.math.abs(it.num("position_fp") ?: it.num("position") ?: 0.0) > 1e-9
    }
    val foreignPositions = positions.mapNotNull { it.str("ticker") }.filter { it !in knownTickers }
    ReconcileResult(foreignOrders, foreignPositions, null)
} catch (e: Exception) {
    ReconcileResult(emptyList(), emptyList(), e.message ?: e.javaClass.simpleName)
}
