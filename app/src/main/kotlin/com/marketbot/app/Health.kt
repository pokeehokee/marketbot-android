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

    /** Called whenever the engine service starts. */
    fun markStarted(ctx: Context, at: Long = System.currentTimeMillis()) = prefs(ctx).edit().putLong("started_at", at).apply()

    /**
     * True when the engine should be running (it was not stopped by the user),
     * is not, and has not started since the phone last booted: Android or the
     * maker's battery manager blocked the restart.
     */
    fun missedBootRestart(ctx: Context, shouldRun: Boolean, running: Boolean,
                          now: Long = System.currentTimeMillis(), sinceBoot: Long = android.os.SystemClock.elapsedRealtime()): Boolean {
        if (!shouldRun || running) return false
        val bootAt = now - sinceBoot
        val started = prefs(ctx).getLong("started_at", 0)
        return started in 1 until bootAt && sinceBoot > 3 * 60_000L   // give the boot receiver a few minutes
    }

    fun crashFile(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    /** Keep the stack trace of an uncaught exception, then let the process die as it would have. */
    fun installCrashLog(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile(ctx).writeText(crashReport(t.name, e))
            }
            prev?.uncaughtException(t, e)
        }
    }

    /**
     * The report kept for a crash. It names the build's commit (retrace it with that
     * build's mapping-N.txt from the release) and, for running out of memory, how
     * full the heap was and how big the long-lived caches had grown.
     */
    fun crashReport(thread: String, e: Throwable, rt: Runtime = Runtime.getRuntime(), at: Instant = Instant.now()): String {
        val mb = { b: Long -> b / 1_048_576 }
        val heap = "heap ${mb(rt.totalMemory() - rt.freeMemory())} MB used of ${mb(rt.maxMemory())} MB"
        val oom = if (e is OutOfMemoryError || e.cause is OutOfMemoryError) "\n" + com.marketbot.core.util.Diagnostics.lines().joinToString("\n") else ""
        return "Marketbot ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
            "$at thread $thread, $heap$oom\n\n${e.stackTraceToString()}"
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


/**
 * Whether Android (or the maker's battery manager) is holding the app back, and
 * the settings page that fixes it on this phone.
 */
object Background {
    enum class Limit { NONE, STANDBY_RARE, RESTRICTED }

    /** From the app's standby bucket and the background-restriction flag; both readable without a permission. */
    fun limit(ctx: Context): Limit {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        if (am?.isBackgroundRestricted == true) return Limit.RESTRICTED
        val bucket = ctx.getSystemService(android.app.usage.UsageStatsManager::class.java)?.appStandbyBucket ?: return Limit.NONE
        return classify(bucket)
    }

    /** RESTRICTED (45) stops jobs and alarms; RARE (40) defers them by hours. ACTIVE..FREQUENT are fine. */
    fun classify(bucket: Int): Limit = when {
        bucket >= android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> Limit.RESTRICTED
        bucket >= android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE -> Limit.STANDBY_RARE
        else -> Limit.NONE
    }

    /**
     * The maker's own battery screen where "sleeping apps" live (Samsung's Device
     * Care), else Android's app page, whose Battery entry has "Unrestricted".
     */
    fun settingsIntents(ctx: Context, manufacturer: String = Build.MANUFACTURER): List<android.content.Intent> {
        val oem = when (manufacturer.lowercase()) {
            "samsung" -> listOf(
                android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
            "xiaomi", "redmi", "poco" -> listOf(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            else -> emptyList()
        }.map { android.content.Intent().setComponent(it).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        val app = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return oem + app
    }

    /** Open the first of [settingsIntents] that this phone can show. */
    fun openSettings(ctx: Context) {
        for (i in settingsIntents(ctx)) if (runCatching { ctx.startActivity(i) }.isSuccess) return
    }

    /** The most recent pause the engine noticed (a late pass), if within [withinH] hours: when and how long. */
    fun recentGap(raw: String?, now: Instant = Instant.now(), withinH: Long = 24): Pair<Instant, Long>? {
        val parts = raw?.split('|') ?: return null
        val at = runCatching { Instant.parse(parts[0]) }.getOrNull() ?: return null
        val secs = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return if (java.time.Duration.between(at, now).toHours() < withinH) at to secs else null
    }
}
