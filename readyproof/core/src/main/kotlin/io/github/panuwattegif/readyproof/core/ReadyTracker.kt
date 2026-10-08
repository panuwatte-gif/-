package io.github.panuwattegif.readyproof.core

/**
 * Remembers which orders are sitting in the Ready tab right now and whether each one already has
 * a good screenshot. An order is photographed once per stay in the tab: it is not photographed
 * again while it waits, however long the rider takes, but a new order is always photographed even
 * when older orders are still listed. When the same order number comes back later (Grab reuses
 * numbers) it is a new stay and gets a new shot.
 */
class ReadyTracker(
    /** An order must be missing this long from a complete view of the list before it counts as gone. */
    private val goneAfterMs: Long = 15_000,
) {
    class Stay(val gf: String, val since: Long) {
        var lastSeen: Long = since
        /** Time of the verified shot, null = still needs one. */
        var shotAt: Long? = null
        var missingSince: Long? = null
        /** Shot attempts that could not be verified (order cut off, list moved ...). */
        var failures: Int = 0
    }

    private val stays = LinkedHashMap<String, Stay>()

    /** Orders seen in the Ready list now. Returns the ones that still need a shot. */
    @Synchronized
    fun seen(gfs: Collection<String>, now: Long): List<String> {
        for (gf in gfs) {
            val s = stays.getOrPut(gf) { Stay(gf, now) }
            s.lastSeen = now
            s.missingSince = null
        }
        return pending()
    }

    /**
     * The whole list is known ([all] = every order in it): it fits on one screen, or a sweep went
     * from top to bottom. Orders not in it have left (a rider picked them up).
     */
    @Synchronized
    fun complete(all: Collection<String>, now: Long) {
        seen(all, now)
        val it = stays.values.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.gf in all) continue
            val since = s.missingSince
            if (since == null) {
                s.missingSince = now
            } else if (now - since >= goneAfterMs) {
                it.remove()
            }
        }
    }

    @Synchronized
    fun shot(gfs: Collection<String>, now: Long) {
        for (gf in gfs) {
            val s = stays.getOrPut(gf) { Stay(gf, now) }
            s.shotAt = now
        }
    }

    @Synchronized
    fun failed(gfs: Collection<String>) {
        for (gf in gfs) stays[gf]?.let { it.failures++ }
    }

    /** Orders in the Ready tab that have no verified shot yet. */
    @Synchronized
    fun pending(): List<String> = stays.values.filter { it.shotAt == null }.map { it.gf }

    /** Orders believed to be in the Ready tab now. */
    @Synchronized
    fun present(): List<String> = stays.values.filter { it.missingSince == null }.map { it.gf }

    @Synchronized
    fun stay(gf: String): Stay? = stays[gf]

    @Synchronized
    fun clear() = stays.clear()

    /**
     * After a restart: orders photographed in the Ready tab during the last [lookbackMs] are taken
     * as still waiting and already photographed. The next complete view of the list corrects it.
     */
    @Synchronized
    fun seed(records: List<Record>, now: Long, lookbackMs: Long = 90 * 60_000L) {
        for (r in records.sortedBy { it.t }) {
            if (r.uri == null || now - r.t > lookbackMs || r.t > now) continue
            for (item in r.items) {
                if (item.type != ObsType.READY) continue
                val s = stays.getOrPut(item.gf) { Stay(item.gf, r.t) }
                s.shotAt = r.t
                s.lastSeen = r.t
            }
        }
    }
}
