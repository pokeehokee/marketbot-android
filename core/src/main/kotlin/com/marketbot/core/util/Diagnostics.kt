package com.marketbot.core.util

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sizes of the long-lived in-memory structures (caches, inboxes, queues), for the
 * diagnostics screen and the crash report. A probe holds its owner weakly, so
 * tracking something never keeps it alive; pass a size function that reads the
 * owner it is given rather than capturing it.
 */
object Diagnostics {
    private class Probe(val owner: WeakReference<Any>, val name: String, val size: (Any) -> Int)
    private val probes = CopyOnWriteArrayList<Probe>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> track(owner: T, name: String, size: (T) -> Int) {
        probes.removeIf { it.owner.get() == null }
        probes += Probe(WeakReference(owner), name) { size(it as T) }
    }

    /** Name and current size of every structure still alive; -1 when reading it failed. */
    fun sizes(): List<Pair<String, Int>> = probes.mapNotNull { p ->
        p.owner.get()?.let { o -> p.name to runCatching { p.size(o) }.getOrDefault(-1) }
    }

    fun lines(): List<String> = sizes().map { (n, s) -> "$n: $s" }
}
