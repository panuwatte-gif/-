package io.github.panuwattegif.readyproof.core

/** Absence is observed across whole Ready-list passes, never across individual viewports. */
class ReadyStayTracker {
    private val known = linkedSetOf<String>()
    private val pass = linkedSetOf<String>()
    private val absent = mutableMapOf<String, Pair<Long, Int>>()

    fun observe(gfs: Collection<String>) {
        known.addAll(gfs)
        pass.addAll(gfs)
        gfs.forEach { absent.remove(it) }
    }

    fun finish(now: Long, entireList: Boolean): List<String> {
        if (!entireList) { pass.clear(); return emptyList() }
        val gone = known.filter { it !in pass }.filter { gf ->
            val old = absent[gf]
            val next = (old?.first ?: now) to ((old?.second ?: 0) + 1)
            absent[gf] = next
            next.second >= 2 && now - next.first >= 15_000
        }
        known.removeAll(gone.toSet())
        gone.forEach { absent.remove(it) }
        pass.clear()
        return gone
    }

    fun clear() { known.clear(); pass.clear(); absent.clear() }
}
