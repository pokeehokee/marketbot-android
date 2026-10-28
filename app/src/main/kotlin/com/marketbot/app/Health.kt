package com.marketbot.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import java.time.Instant

/** Whether the phone can reach the venues, honouring the Wi-Fi-only setting. */
object Connectivity {
    fun wifiOnly(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("wifi_only", false)
    fun setWifiOnly(ctx: Context, on: Boolean) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("wifi_only", on).apply()

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !wifiOnly(ctx) || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/**
 * Records that let the app tell the user, afterwards, when it was not running:
 * a heartbeat written while the engine runs, and the last crash.
 */
object Health {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)

    fun beat(ctx: Context, at: Long = System.currentTimeMillis()) = prefs(ctx).edit().putLong("heartbeat", at).apply()
    fun clearBeat(ctx: Context) = prefs(ctx).edit().remove("heartbeat").apply()
    fun lastBeat(ctx: Context): Long? = prefs(ctx).getLong("heartbeat", 0).takeIf { it > 0 }

    /**
     * The gap since the last heartbeat when the engine should have been running,
     * or null if it was short (an ordinary restart). Clears the heartbeat so a gap
     * is reported once.
     */
    fun gapOnStart(ctx: Context, now: Long = System.currentTimeMillis(), threshold: Long = 10 * 60_000L): Pair<Instant, Instant>? {
        val last = lastBeat(ctx) ?: return null
        prefs(ctx).edit().remove("heartbeat").apply()
        return if (now - last > threshold) Instant.ofEpochMilli(last) to Instant.ofEpochMilli(now) else null
    }

    fun crashFile(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    /** Keep the stack trace of an uncaught exception, then let the process die as it would have. */
    fun installCrashLog(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile(ctx).writeText("Marketbot ${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
                    "${Instant.now()} thread ${t.name}\n\n${e.stackTraceToString()}")
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun lastCrash(ctx: Context): String? = crashFile(ctx).takeIf { it.exists() }?.readText()
    fun clearCrash(ctx: Context) { crashFile(ctx).delete() }

    /** What this phone's maker needs, beyond Android's own battery exemption, to leave a background app alone. */
    fun oemAdvice(manufacturer: String = Build.MANUFACTURER): String? = when (manufacturer.lowercase()) {
        "samsung" -> "Samsung: Settings > Battery > Background usage limits: remove Marketbot from Sleeping and Deep sleeping apps, and add it to Never sleeping apps."
        "xiaomi", "redmi", "poco" -> "Xiaomi: Settings > Apps > Marketbot > Battery saver: No restrictions, and turn on Autostart."
        "oneplus", "oppo", "realme" -> "${manufacturer.replaceFirstChar { it.uppercase() }}: Settings > Battery > Marketbot: allow background activity and auto-launch; turn off Optimise battery use."
        "huawei", "honor" -> "Huawei: Settings > Battery > App launch: set Marketbot to Manage manually with all three switches on."
        "vivo", "iqoo" -> "Vivo: Settings > Battery > Background power consumption: allow Marketbot high background power use."
        "google" -> null
        else -> null
    }
}
