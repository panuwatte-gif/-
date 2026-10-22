package io.github.panuwattegif.readyproof.core

/**
 * Orders in the Preparing tab as last read: when each one's countdown runs out and whether its
 * red 0:00 card has been photographed. Tells the watcher when a look at the Preparing tab is due.
 */
class PrepTracker {
    private class Entry(
        var deadline: Long,
        var lastRecorded: Long,
        var recordedRanOut: Boolean,
        /** Last time the Preparing tab was looked at while this order was known. */
        var checkedAt: Long,
        var overdueShotAt: Long? = null,
    )

    private val entries = HashMap<String, Entry>()

    /**
     * A reading of the orders on the Preparing tab at [now]. Returns the readings worth keeping in
     * the log: an order seen for the first time, a countdown that reached 0:00, or one that moved
     * by more than [MOVE_MS] (more time given).
     */
    @Synchronized
    fun read(items: List<Item>, now: Long): List<Item> {
        val keep = ArrayList<Item>()
        for (item in items) {
            if (item.type != ObsType.PREPARING) continue
            val due = item.dueSeconds ?: continue
            val deadline = now + due * 1000L
            val e = entries[item.gf]
            if (e == null) {
                entries[item.gf] = Entry(deadline, deadline, due == 0, now)
                keep += item
                continue
            }
            e.deadline = if (due == 0) minOf(e.deadline, now) else deadline
            e.checkedAt = now
            val ranOut = due == 0
            if ((ranOut && !e.recordedRanOut) || (!ranOut && kotlin.math.abs(deadline - e.lastRecorded) > MOVE_MS)) {
                e.lastRecorded = deadline
                e.recordedRanOut = ranOut
                keep += item
            }
        }
        return keep
    }

    /** Orders whose red 0:00 card still needs its one photo. */
    @Synchronized
    fun needsOverdueShot(gf: String): Boolean = entries[gf]?.overdueShotAt == null

    @Synchronized
    fun shotOverdue(gfs: Collection<String>, now: Long) {
        gfs.forEach { entries[it]?.overdueShotAt = now }
    }

    /** The Preparing tab was looked at [now] (whatever it showed). */
    @Synchronized
    fun checked(now: Long) {
        entries.values.forEach { it.checkedAt = now }
    }

    /** The whole Preparing list was on screen: orders not on it have left the tab. */
    @Synchronized
    fun complete(listed: Collection<String>) {
        entries.keys.retainAll(listed.toSet())
    }

    /** These orders reached the Ready tab. */
    @Synchronized
    fun left(gfs: Collection<String>) {
        gfs.forEach { entries.remove(it) }
    }

    /**
     * An order's countdown ran out [graceMs] ago and nobody has looked at the Preparing tab since:
     * time to photograph its red card (or find it already pressed).
     */
    @Synchronized
    fun runOutUnseen(now: Long, graceMs: Long): Boolean = entries.values.any { e ->
        e.overdueShotAt == null && e.deadline + graceMs <= now && e.checkedAt < e.deadline + graceMs
    }

    @Synchronized
    fun known(): Set<String> = entries.keys.toSet()

    @Synchronized
    fun clear() = entries.clear()

    /** Rebuilds memory from today's log after a restart. */
    @Synchronized
    fun seed(records: List<Record>) {
        entries.clear()
        for (r in records.sortedBy { it.t }) {
            for (item in r.items) {
                when (item.type) {
                    ObsType.PREPARING -> {
                        val due = item.dueSeconds ?: continue
                        val deadline = r.t + due * 1000L
                        val e = entries.getOrPut(item.gf) { Entry(deadline, deadline, due == 0, r.t) }
                        e.deadline = deadline
                        e.lastRecorded = deadline
                        e.recordedRanOut = due == 0
                        e.checkedAt = maxOf(e.checkedAt, r.t)
                        if (r.uri != null && due == 0) e.overdueShotAt = r.t
                    }
                    ObsType.READY -> entries.remove(item.gf)
                    else -> {}
                }
            }
        }
    }

    companion object {
        /** A countdown that moved more than this was given more time: worth a new log line. */
        const val MOVE_MS = 30_000L
    }
}
