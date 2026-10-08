package io.github.panuwattegif.readyproof.core

/**
 * Remembers which observations were already recorded so scrolling back and forth over the same
 * orders does not produce a flood of identical screenshots.
 */
class Deduper {
    private val seen = HashMap<String, Long>()

    @Synchronized
    fun isFresh(key: String, now: Long, windowMs: Long): Boolean {
        val last = seen[key] ?: return true
        return now - last >= windowMs
    }

    @Synchronized
    fun mark(keys: Collection<String>, now: Long) {
        keys.forEach { seen[it] = now }
        if (seen.size > 5000) prune(now - 48L * 3600_000)
    }

    @Synchronized
    fun forget(keys: Collection<String>) {
        keys.forEach { seen.remove(it) }
    }

    /** Rebuilds memory from the log after the service restarts. */
    @Synchronized
    fun seed(records: List<Record>) {
        for (r in records) for (item in r.items) {
            val key = keyOf(item) ?: continue
            val prev = seen[key]
            if (prev == null || prev < r.t) seen[key] = r.t
        }
    }

    /** Observations not seen within their repeat window. */
    fun fresh(items: List<Item>, now: Long, cfg: Config): List<Item> =
        items.filter { item ->
            val key = keyOf(item) ?: return@filter true
            isFresh(key, now, windowMs(item.type, cfg))
        }

    private fun prune(olderThan: Long) {
        seen.entries.removeAll { it.value < olderThan }
    }

    companion object {
        /** Identity of an observation; history rows include the finish time because order numbers repeat. */
        fun keyOf(item: Item): String? = when (item.type) {
            ObsType.READY -> "READY|${item.gf}"
            ObsType.DELAY -> "DELAY|${item.gf}|${item.doneAt ?: "-"}"
            ObsType.DONE -> "DONE|${item.gf}|${item.doneAt ?: "-"}"
            ObsType.PRESS, ObsType.VISIBLE -> null
        }

        /** History rows are photographed once; the Ready tab is handled by [ReadyTracker]. */
        @Suppress("UNUSED_PARAMETER")
        fun windowMs(type: ObsType, cfg: Config): Long = when (type) {
            ObsType.READY -> 10 * 60_000L
            ObsType.DELAY, ObsType.DONE -> 36L * 3600_000
            ObsType.PRESS, ObsType.VISIBLE -> 0L
        }
    }
}
