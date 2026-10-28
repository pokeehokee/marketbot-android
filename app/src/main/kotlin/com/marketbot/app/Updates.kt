package com.marketbot.app

import com.marketbot.core.util.JSON
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Checks the repository's releases (tagged build-N by CI) for a build newer than this one. */
object Updates {
    data class Release(val build: Int, val tag: String, val pageUrl: String, val apkUrl: String?)

    /** The newest release in a GitHub `/releases` response, ignoring drafts and tags that are not `build-N`. */
    fun newest(body: String): Release? = (JSON.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        if (o["draft"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val n = Regex("^build-(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val apk = (o["assets"] as? JsonArray).orEmpty().mapNotNull { a -> (a as? JsonObject)?.get("browser_download_url")?.jsonPrimitive?.contentOrNull }
            .firstOrNull { it.endsWith(".apk") }
        Release(n, tag, o["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/${BuildConfig.UPDATE_REPO}/releases", apk)
    }.maxByOrNull { it.build }

    /** A private repository needs a read-only token; a public one does not. */
    fun check(token: String?, client: OkHttpClient = OkHttpClient(), base: String = "https://api.github.com"): Result<Release?> = runCatching {
        val rb = Request.Builder().url("$base/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=10")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "marketbot-android")
        if (!token.isNullOrBlank()) rb.header("Authorization", "Bearer ${token.trim()}")
        client.newCall(rb.build()).execute().use { r ->
            when (r.code) {
                200 -> newest(r.body?.string().orEmpty())
                401, 403 -> error("GitHub refused the request (${r.code}). Check the token.")
                404 -> error("Releases not found. If the repository is private, add a read-only token.")
                else -> error("GitHub answered ${r.code}.")
            }
        }
    }
}
