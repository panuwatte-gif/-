package io.github.panuwattegif.readyproofclean.core

/** A partial viewport cannot prove an order left Ready. Only a complete sweep can. */
class ReadyStays(private val absenceMs: Long = 15_000L) {
    private data class Stay(var proved: Boolean = false, var missingSince: Long? = null)
    private val stays = LinkedHashMap<String, Stay>()
    @Synchronized fun clear() = stays.clear()
    @Synchronized fun seen(gfs: Collection<String>) {
        gfs.forEach { stays.getOrPut(it) { Stay() }.missingSince = null }
    }
    @Synchronized fun proved(gfs: Collection<String>) {
        gfs.forEach { stays.getOrPut(it) { Stay() }.proved = true }
    }
    @Synchronized fun needsProof(gf: String): Boolean = stays[gf]?.proved != true
    @Synchronized fun complete(all: Collection<String>, now: Long) {
        seen(all)
        val iterator = stays.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in all) continue
            val missing = entry.value.missingSince
            if (missing == null) entry.value.missingSince = now
            else if (now - missing >= absenceMs) iterator.remove()
        }
    }
}
