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
        /** Photo of the red card checked to show it (number and line still in place after it). */
        var overdueShotAt: Long? = null,
        /** Photo of the red card kept although the screen moved while it was taken (to look at). */
        var overdueBackupAt: Long? = null,
        /** First seen red (0:00) on the Preparing tab. */
        var seenRedAt: Long? = null,
        /** Looks at its red card that ended without any photo (not on screen, capture failed). */
        var overdueMisses: Int = 0,
        /** Seen red at the last look but no photo saved: look again. */
        var retry: Boolean = false,
        /** Last time it was on the Preparing tab, and what its countdown said then. */
        var lastSeen: Long = checkedAt,
        var lastCountdown: String? = null,
    ) {
        val hasOverduePhoto: Boolean get() = overdueShotAt != null || overdueBackupAt != null
    }

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
                entries[item.gf] = Entry(deadline, deadline, due == 0, now, seenRedAt = if (due == 0) now else null,
                    lastCountdown = item.countdown)
                keep += item
                continue
            }
            e.deadline = if (due == 0) minOf(e.deadline, now) else deadline
            e.checkedAt = now
            e.lastSeen = now
            e.lastCountdown = item.countdown
            val ranOut = due == 0
            if (ranOut && e.seenRedAt == null) e.seenRedAt = now
            if ((ranOut && !e.recordedRanOut) || (!ranOut && kotlin.math.abs(deadline - e.lastRecorded) > MOVE_MS)) {
                e.lastRecorded = deadline
                e.recordedRanOut = ranOut
                keep += item
            }
        }
        return keep
    }

    /**
     * The red card of [gf] still needs a photo: none yet, or only a kept-but-unchecked one while
     * the card is now completely on screen ([whole]). Never more than that.
     */
    @Synchronized
    fun needsOverdueShot(gf: String, whole: Boolean = true): Boolean {
        val e = entries[gf] ?: return true
        return e.overdueShotAt == null && (e.overdueBackupAt == null || whole)
    }

    /** Photos of red cards saved and checked. */
    @Synchronized
    fun shotOverdue(gfs: Collection<String>, now: Long) {
        gfs.forEach { entries[it]?.overdueShotAt = now }
    }

    /** Photos of red cards saved, but the screen moved while they were taken: kept to look at. */
    @Synchronized
    fun backupOverdue(gfs: Collection<String>, now: Long) {
        gfs.forEach { entries[it]?.overdueBackupAt = now }
    }

    /**
     * Red cards seen without any photo saved (not on screen, capture or save failed): look again.
     * [counts]: a look the app made itself; only those use up the [MAX_MISSES] tries (someone
     * scrolling the tab shows the same card again and again).
     */
    @Synchronized
    fun missedOverdue(gfs: Collection<String>, counts: Boolean = true) {
        gfs.forEach { gf ->
            entries[gf]?.let {
                if (counts) it.overdueMisses++
                it.retry = true
            }
        }
    }

    /**
     * The Preparing tab was looked at [now]; [seen] = the orders it showed (null = not known). An
     * order that was not on it cannot be photographed there: no second look for its red card.
     */
    @Synchronized
    fun checked(now: Long, seen: Collection<String>? = null) {
        entries.forEach { (gf, e) ->
            e.checkedAt = now
            if (seen != null && gf !in seen) e.retry = false
        }
    }

    /** An order gone from the Preparing tab: last seen there at [lastSeen], its countdown then. */
    data class Gone(val gf: String, val lastSeen: Long, val countdown: String?)

    /** The whole Preparing list was on screen: orders not on it have left the tab (returned). */
    @Synchronized
    fun complete(listed: Collection<String>): List<Gone> {
        val keep = listed.toSet()
        val gone = entries.filterKeys { it !in keep }.map { (gf, e) -> Gone(gf, e.lastSeen, e.lastCountdown) }
        entries.keys.retainAll(keep)
        return gone
    }

    /** These orders reached the Ready tab. */
    @Synchronized
    fun left(gfs: Collection<String>) {
        gfs.forEach { entries.remove(it) }
    }

    /**
     * Time to look at the Preparing tab for a red card: an order's countdown ran out [graceMs] ago
     * and nobody has looked since, or its red card was seen but no photo could be saved (at most
     * [MAX_MISSES] tries). Seeing an order, seeing it red and having its photo saved are three
     * different things: only the saved photo ends the job.
     */
    @Synchronized
    fun runOutUnseen(now: Long, graceMs: Long): Boolean = entries.values.any { e ->
        !e.hasOverduePhoto && e.deadline + graceMs <= now &&
            (e.checkedAt < e.deadline + graceMs || (e.retry && e.overdueMisses < MAX_MISSES))
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
                        e.lastSeen = maxOf(e.lastSeen, r.t)
                        e.lastCountdown = item.countdown
                        if (due == 0 && e.seenRedAt == null) e.seenRedAt = r.t
                        if (r.uri != null && due == 0) {
                            if (r.note == BACKUP_NOTE) e.overdueBackupAt = r.t else e.overdueShotAt = r.t
                        }
                    }
                    ObsType.READY, ObsType.LEFT_PREP -> entries.remove(item.gf)
                    else -> {}
                }
            }
        }
    }

    companion object {
        /** A countdown that moved more than this was given more time: worth a new log line. */
        const val MOVE_MS = 30_000L
        /** Looks at a red card that may end without a photo before giving up on it. */
        const val MAX_MISSES = 3
        /** Note on a red-card photo whose screen moved while it was taken. */
        const val BACKUP_NOTE = "PREP_BACKUP รอตรวจสอบ"
    }
}
