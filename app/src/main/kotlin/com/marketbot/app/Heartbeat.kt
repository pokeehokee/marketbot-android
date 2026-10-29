package com.marketbot.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * A dead-man's switch outside the phone. A phone that has crashed or been killed
 * cannot say so; an outside service (healthchecks.io, ntfy, Uptime Kuma) can,
 * when the pings it expects stop arriving. Each ping is a bare GET with no data.
 */
object Heartbeat {
    /** Ping at most this often; the outside check's period should be longer (10-15 min) with a grace period. */
    const val EVERY_MS = 5 * 60_000L
    @Volatile private var lastAt = 0L
    @Volatile var lastResult: String? = null; private set

    private val client by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }

    /** A URL the app will ping: https only, so the token in it is never sent in the clear. */
    fun valid(url: String?): Boolean = url != null && url.startsWith("https://") && url.toHttpUrlOrNull() != null

    /** Ping when due. Returns true when a ping was sent and answered 2xx. */
    fun beatIfDue(url: String?, now: Long = System.currentTimeMillis(), online: Boolean = true): Boolean {
        if (!valid(url) || !online || now - lastAt < EVERY_MS) return false
        lastAt = now
        return ping(url!!)
    }

    /** One ping now (also the Settings "Test" button). */
    fun ping(url: String): Boolean {
        val r = runCatching { client.newCall(Request.Builder().url(url).get().header("User-Agent", "marketbot-heartbeat").build()).execute().use { it.code } }
        lastResult = r.fold({ "HTTP $it at ${java.time.LocalTime.now().withNano(0)}" }, { "failed: ${it.message}" })
        return r.getOrNull()?.let { it in 200..299 } == true
    }
}
