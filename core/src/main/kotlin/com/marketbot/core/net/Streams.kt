package com.marketbot.core.net

import com.marketbot.core.util.JSON
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A signed websocket that stays connected: reconnects with backoff (2 s doubling to
 * 5 min), re-sends its subscriptions after every connect, and never throws into the
 * caller. Messages arrive as JSON objects on OkHttp's reader thread.
 */
class SignedStream(
    private val wsUrl: String,
    private val signer: RequestSigner,
    private val subscriptions: () -> List<JsonObject>,
    private val onMessage: (JsonObject) -> Unit,
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build(),
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "stream").apply { isDaemon = true } },
) {
    @Volatile var connected = false; private set
    val received = AtomicInteger()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    private var backoffS = 2L

    /** The signature covers GET and the path, as for REST; the wss scheme is signed as https. */
    private fun signUrl() = wsUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrl()

    fun start() { stopped = false; connect() }

    fun stop() { stopped = true; ws?.close(1000, "stop"); ws = null; connected = false }

    /** Send one message now; false when not connected (the next connect re-sends the subscriptions anyway). */
    fun send(o: JsonObject): Boolean = connected && ws?.send(JSON.encodeToString(JsonObject.serializer(), o)) == true

    private fun connect() {
        if (stopped) return
        val rb = Request.Builder().url(wsUrl)
        runCatching { signer.headers("GET", signUrl()) }.getOrNull()?.forEach { (k, v) -> rb.header(k, v) } ?: return retry()
        ws = client.newWebSocket(rb.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true; backoffS = 2
                subscriptions().forEach { webSocket.send(JSON.encodeToString(JsonObject.serializer(), it)) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
                received.incrementAndGet()
                runCatching { onMessage(o) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { connected = false; retry() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { connected = false; retry() }
        })
    }

    private fun retry() {
        if (stopped) return
        val wait = backoffS
        backoffS = minOf(backoffS * 2, 300)
        runCatching { scheduler.schedule({ connect() }, wait, TimeUnit.SECONDS) }
    }
}
