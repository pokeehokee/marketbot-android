package com.marketbot.core.store

/**
 * Every ledger row as CSV, for the share sheet and for reviewing a record off the phone.
 * The first eighteen columns are the original export's, in its order; the rest were added
 * so a review can use what the strategies stored with each order: the quoted mid, markouts
 * and queue position live in `extra` (a JSON object, written as-is).
 */
object LedgerCsv {
    val COLUMNS = listOf(
        "strategy", "mode", "venue", "ticker", "action", "price", "contracts", "placed_at", "order_id", "filled", "fill_source",
        "cancelled_at", "resolved_at", "result", "pnl", "pnl_source", "pnl_origin", "hypothetical",
        "id", "client_order_id", "market_kind", "close_time", "queue_ahead", "fill_checked_at", "extra",
    )

    fun header() = COLUMNS.joinToString(",") + "\n"

    fun row(q: QuoteRow): String = listOf(
        q.strategyId, q.mode, q.venue, q.ticker, q.action, q.price, q.contracts, q.placedAt, q.orderId, q.filledContracts, q.fillSource,
        q.cancelledAt, q.resolvedAt, q.result, q.pnl, q.pnlSource, q.pnlOrigin, q.hypothetical,
        q.id, q.clientOrderId, q.marketKind, q.closeTime, q.queueAhead, q.fillCheckedAt, q.extra.takeIf { it.isNotEmpty() },
    ).joinToString(",") { cell(it) } + "\n"

    private fun cell(x: Any?) = "\"" + (x?.toString() ?: "").replace("\"", "\"\"") + "\""
}
