package com.marketbot.core.net

import com.marketbot.core.util.JSON
import com.marketbot.core.util.obj
import com.marketbot.core.util.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** An error response from a venue, with the venue's own error code when it sent one. */
open class ApiException(
    val status: Int,
    val code: String?,
    message: String,
    val body: String? = null,
) : IOException(message) {
    val retryable: Boolean get() = status == 429 || status >= 500
}

class RateLimited(code: String?, message: String) : ApiException(429, code, message)
class AuthRejected(status: Int, code: String?, message: String) : ApiException(status, code, message)
class NotFound(code: String?, message: String) : ApiException(404, code, message)

/** Spaces calls to at most [perSecond] per second across coroutines. */
class RateLimiter(perSecond: Double) {
    private val gapNanos = (1_000_000_000.0 / perSecond).toLong()
    private var next = 0L
    private val mutex = Mutex()

    suspend fun acquire() {
        val wait = mutex.withLock {
            val now = System.nanoTime()
            val w = next - now
            next = maxOf(now, next) + gapNanos
            w
        }
        if (wait > 0) delay(wait / 1_000_000)
    }
}

/** Headers to add to one request; the signer sees the method and path exactly as sent. */
fun interface RequestSigner {
    fun headers(method: String, url: HttpUrl): Map<String, String>
}

/**
 * Thin JSON-over-HTTPS client. Retries 429/5xx and transport errors for
 * idempotent methods with exponential backoff; never retries a POST, because
 * a retried order create is how a duplicate order happens.
 */
class JsonHttp(
    private val base: String,
    private val client: OkHttpClient = defaultClient(),
    private val limiter: RateLimiter? = null,
    private val userAgent: String = "marketbot-android/0.1",
    private val maxRetries: Int = 3,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    fun url(path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl {
        val b = (base + path).toHttpUrl().newBuilder()
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.build()
    }

    suspend fun get(path: String, query: List<Pair<String, String>> = emptyList(), signer: RequestSigner? = null): JsonObject =
        request("GET", url(path, query), null, signer)

    suspend fun post(path: String, body: JsonElement, signer: RequestSigner?): JsonObject =
        request("POST", url(path), JSON.encodeToString(JsonElement.serializer(), body), signer)

    suspend fun delete(path: String, query: List<Pair<String, String>> = emptyList(), signer: RequestSigner?): JsonObject =
        request("DELETE", url(path, query), null, signer)

    /** A DELETE that carries a body (batch cancels). Retried like any DELETE: cancelling twice is harmless. */
    suspend fun deleteWithBody(path: String, body: JsonElement, signer: RequestSigner?): JsonObject =
        request("DELETE", url(path), JSON.encodeToString(JsonElement.serializer(), body), signer)

    /** PUT is not retried: it is only used for state changes the caller checks afterwards. */
    suspend fun put(path: String, body: JsonElement, query: List<Pair<String, String>> = emptyList(), signer: RequestSigner?): JsonObject =
        request("PUT", url(path, query), JSON.encodeToString(JsonElement.serializer(), body), signer)

    suspend fun request(method: String, url: HttpUrl, body: String?, signer: RequestSigner?): JsonObject {
        val idempotent = method == "GET" || method == "DELETE"
        var attempt = 0
        while (true) {
            limiter?.acquire()
            val rb = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json")
            if (body != null) rb.header("Content-Type", "application/json")
            signer?.headers(method, url)?.forEach { (k, v) -> rb.header(k, v) }
            rb.method(method, body?.toRequestBody("application/json".toMediaType()))
            try {
                val sentAt = System.currentTimeMillis()
                val (code, ok, text) = try {
                    client.newCall(rb.build()).await().use { resp ->
                        val t = resp.body?.string().orEmpty()
                        NetStats.record(url.host, t.length.toLong() + (body?.length ?: 0) + 400, resp.header("Date"), sentAt, System.currentTimeMillis())
                        Triple(resp.code, resp.isSuccessful, t)
                    }
                } catch (e: IOException) {
                    if (ApiJournal.recording) ApiJournal.record(ApiJournal.Entry(java.time.Instant.now(), method, url.toString(), null,
                        System.currentTimeMillis() - sentAt, body, null, e.message ?: e.javaClass.simpleName))
                    throw e
                }
                if (ApiJournal.recording) ApiJournal.record(ApiJournal.Entry(java.time.Instant.now(), method, url.toString(), code,
                    System.currentTimeMillis() - sentAt, body, text, null))
                if (ok) {
                    if (text.isBlank()) return JsonObject(emptyMap())
                    val el = JSON.parseToJsonElement(text)
                    return el as? JsonObject ?: JsonObject(mapOf("data" to el))
                }
                val err = errorOf(code, text, url)
                if (idempotent && err.retryable && attempt < maxRetries) {
                    sleep(backoffMs(attempt)); attempt++; continue
                }
                throw err
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                if (idempotent && attempt < maxRetries) {
                    sleep(backoffMs(attempt)); attempt++; continue
                }
                throw e
            }
        }
    }

    private fun backoffMs(attempt: Int) = 500L * (1L shl attempt)

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        /** Kalshi: `{"error": {"code", "message"}}` or `{"code", "message"}`; Polymarket: `{"code", "message"}`. */
        fun errorOf(status: Int, text: String, url: HttpUrl): ApiException {
        var code: String? = null
        var msg = text.take(300)
        runCatching {
            val o = JSON.parseToJsonElement(text) as JsonObject
            val e = o.obj("error") ?: o
            code = e.str("code")
            msg = listOfNotNull(e.str("message") ?: o.str("error"), e.str("details")).joinToString(" ").ifBlank { msg }
        }
        var label = "$status ${url.encodedPath}: $msg"
        // A signature rejected because the phone's clock is off looks like a bad key; say which it is.
        if (status == 401 || status == 403) NetStats.skewMs(url.host)?.takeIf { kotlin.math.abs(it) > 5_000 }?.let {
            label += " (the phone's clock is off by ${kotlin.math.abs(it) / 1000} s from ${url.host}; turn on automatic date and time)"
        }
        return when (status) {
            429 -> RateLimited(code, label)
            401, 403 -> AuthRejected(status, code, label)
            404 -> NotFound(code, label)
            else -> ApiException(status, code, label, text.take(2000))
        }
        }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
