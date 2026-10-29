package com.marketbot.core

import com.marketbot.core.kalshi.KalshiClient
import com.marketbot.core.net.JsonHttp
import com.marketbot.core.net.KalshiSigner
import com.marketbot.core.util.JSON
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scriptable Kalshi exchange on MockWebServer. Tests set the market
 * listing, balance, orders and settlements; every request is recorded so a
 * test can assert exactly what was (or was not) sent.
 */
class FakeKalshi : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val bodies = CopyOnWriteArrayList<JsonObject>()
    val amends = CopyOnWriteArrayList<JsonObject>()
    var amendFails = false

    /** series ticker -> markets */
    var markets: MutableMap<String, List<String>> = mutableMapOf()
    var marketByTicker: MutableMap<String, String> = mutableMapOf()
    var balance = """{"balance":50000,"balance_dollars":"500.0000","portfolio_value":0,"updated_ts":1}"""
    var settlements: String? = """{"settlements":[],"cursor":""}"""
    var orders: MutableMap<String, String> = mutableMapOf()
    var restingOrders = """{"orders":[],"cursor":""}"""
    var positions = """{"market_positions":[],"cursor":""}"""
    var seriesFeeType = "quadratic"
    var trades = """{"trades":[],"cursor":""}"""
    private var nextOrder = 1
    /** Order groups created: id -> (limit, shard); and what was done to them. */
    val groups = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    val groupActions = CopyOnWriteArrayList<String>()
    /** When set, an order carrying an order group is refused as Kalshi does once the group tripped. */
    var groupTripped = false
    /** The refusal message used while [groupTripped]; Kalshi may reword it. */
    var groupRefusal = """{"error":{"code":"order_group_triggered","message":"order group is triggered"}}"""
    val batchCancels = CopyOnWriteArrayList<JsonObject>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath.removePrefix("/trade-api/v2")
                val q = request.requestUrl!!
                fun ok(b: String) = MockResponse().setResponseCode(200).setBody(b)
                return when {
                    request.method == "POST" && path == "/portfolio/order_groups/create" -> {
                        val body = JSON.parseToJsonElement(request.body.readUtf8()) as JsonObject
                        val id = "group-${groups.size + 1}"
                        groups[id] = body["contracts_limit"].toString().toInt() to (body["exchange_index"]?.toString()?.toInt() ?: 0)
                        MockResponse().setResponseCode(201).setBody("""{"order_group_id":"$id","subaccount":0}""")
                    }
                    request.method == "GET" && path.startsWith("/portfolio/order_groups/") -> {
                        val id = path.removePrefix("/portfolio/order_groups/")
                        if (groups.containsKey(id)) ok("""{"is_auto_cancel_enabled":true,"contracts_limit_fp":"${groups[id]!!.first}.00","orders":[]}""")
                        else MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":"order group not found"}}""")
                    }
                    request.method == "PUT" && path.startsWith("/portfolio/order_groups/") -> {
                        groupActions += path.removePrefix("/portfolio/order_groups/") + "?" + (q.queryParameter("exchange_index") ?: "")
                        ok("{}")
                    }
                    request.method == "DELETE" && path == "/portfolio/events/orders/batched" -> {
                        val body = JSON.parseToJsonElement(request.body.readUtf8()) as JsonObject
                        batchCancels += body
                        val ids = (body["orders"] as kotlinx.serialization.json.JsonArray).map { (it as JsonObject)["order_id"].toString().trim('"') }
                        ok("""{"orders":[${ids.joinToString(",") { """{"order_id":"$it","reduced_by":"5.00","ts_ms":1}""" }}]}""")
                    }
                    request.method == "POST" && path == "/portfolio/events/orders" && groupTripped && request.body.peek().readUtf8().contains("order_group_id") ->
                        MockResponse().setResponseCode(400).setBody(groupRefusal)
                    request.method == "POST" && path == "/portfolio/events/orders" -> {
                        val body = JSON.parseToJsonElement(request.body.readUtf8()) as JsonObject
                        bodies += body
                        val id = "order-${nextOrder++}"
                        orders[id] = """{"order":{"order_id":"$id","ticker":${body["ticker"]},"status":"resting","fill_count_fp":"0.00","remaining_count_fp":${body["count"]},"initial_count_fp":${body["count"]}}}"""
                        MockResponse().setResponseCode(201).setBody("""{"order_id":"$id","client_order_id":${body["client_order_id"]},"fill_count":"0.00","remaining_count":${body["count"]},"ts_ms":1}""")
                    }
                    request.method == "POST" && path.endsWith("/amend") -> {
                        amends += JSON.parseToJsonElement(request.body.readUtf8()) as JsonObject
                        if (amendFails) MockResponse().setResponseCode(404).setBody("{}")
                        else ok("""{"order_id":"${path.split('/').dropLast(1).last()}","remaining_count":"5.00","fill_count":"0.00","ts_ms":1}""")
                    }
                    request.method == "POST" && path == "/portfolio/intra_exchange_instance_transfer" -> {
                        bodies += JSON.parseToJsonElement(request.body.readUtf8()) as JsonObject
                        ok("""{"transfer_id":"tr-1"}""")
                    }
                    request.method == "DELETE" -> ok("""{"order_id":"${path.substringAfterLast('/')}","reduced_by":"5.00","ts_ms":1}""")
                    path == "/markets" -> ok("""{"markets":[${(markets[q.queryParameter("series_ticker") ?: ("event:" + q.queryParameter("event_ticker"))]).orEmpty().joinToString(",")}],"cursor":""}""")
                    path.startsWith("/markets/trades") -> ok(trades)
                    path.startsWith("/markets/") -> ok("""{"market":${marketByTicker[path.removePrefix("/markets/")] ?: "{}"}}""")
                    path.startsWith("/series/") -> ok("""{"series":{"fee_type":"$seriesFeeType","fee_multiplier":1}}""")
                    path == "/portfolio/balance" -> ok(balance)
                    path == "/portfolio/settlements" -> settlements?.let { ok(it) } ?: MockResponse().setResponseCode(503).setBody("""{"error":{"code":"unavailable"}}""")
                    path == "/portfolio/orders" -> ok(restingOrders)
                    path == "/portfolio/positions" -> ok(positions)
                    path.startsWith("/portfolio/orders/") -> orders[path.substringAfterLast('/')]?.let { ok(it) } ?: MockResponse().setResponseCode(404).setBody("{}")
                    path == "/exchange/status" -> ok("""{"exchange_active":true,"trading_active":true,"exchange_index_statuses":[{"exchange_index":2,"exchange_active":true,"trading_active":true}]}""")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
    }

    val base: String get() = server.url("/trade-api/v2").toString().trimEnd('/')

    fun client(signed: Boolean, clock: com.marketbot.core.util.Clock = com.marketbot.core.util.Clock.SYSTEM, subaccount: Int = 0): KalshiClient {
        val pem = golden("signatures").o()["rsa_pss"].o()["pkcs1_pem"].s!!
        return KalshiClient(if (signed) KalshiSigner("kid", pem) else null, base, clock = clock, http = JsonHttp(base, sleep = {}), subaccount = subaccount)
    }

    fun posts() = requests.filter { it.method == "POST" }
    fun deletes() = requests.filter { it.method == "DELETE" }

    override fun close() = server.shutdown()

    companion object {
        fun market(ticker: String, bid: Double, ask: Double, close: String, status: String = "active", result: String = "",
                   askSize: Double = 10.0, exchangeIndex: Int = 0) =
            """{"ticker":"$ticker","event_ticker":"${ticker.substringBeforeLast('-')}","status":"$status","result":"$result",
               "close_time":"$close","yes_bid_dollars":"${"%.4f".format(bid)}","yes_ask_dollars":"${"%.4f".format(ask)}",
               "no_bid_dollars":"${"%.4f".format(1 - ask)}","yes_ask_size_fp":"$askSize","yes_bid_size_fp":"5.00",
               "volume_24h_fp":"50.00","open_interest_fp":"100.00","exchange_index":$exchangeIndex,
               "price_ranges":[{"start":"0.0000","end":"1.0000","step":"0.0100"}],"strike_type":"between"}"""
    }
}
