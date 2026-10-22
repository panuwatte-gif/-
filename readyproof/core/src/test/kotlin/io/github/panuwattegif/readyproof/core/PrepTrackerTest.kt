package io.github.panuwattegif.readyproof.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrepTrackerTest {
    private fun prep(gf: String, countdown: String) = Item(gf, ObsType.PREPARING, countdown = countdown)

    @Test
    fun aNewOrderIsLoggedOnceAndALookIsDueWhenItsTimeRunsOut() {
        val t = PrepTracker()
        // 11:10:00 GF-992 shows 1:40 → runs out at 11:11:40
        assertEquals(listOf("GF-992"), t.read(listOf(prep("GF-992", "1:40")), 0).map { it.gf })
        // a minute later it shows 0:40: same deadline, nothing new to log, nothing due
        assertTrue(t.read(listOf(prep("GF-992", "0:40")), 60_000).isEmpty())
        assertFalse(t.runOutUnseen(100_000 + 2_000, 3_000))
        // 3 s after it ran out without anybody looking: time to look (and photograph the red card)
        assertTrue(t.runOutUnseen(100_000 + 3_000, 3_000))
        // the look finds it red: logged, photographed once, nothing more due
        assertEquals(listOf("GF-992"), t.read(listOf(prep("GF-992", "0:00")), 104_000).map { it.gf })
        assertTrue(t.needsOverdueShot("GF-992"))
        t.shotOverdue(listOf("GF-992"), 104_000)
        assertFalse(t.needsOverdueShot("GF-992"))
        assertFalse(t.runOutUnseen(200_000, 3_000))
        // still red later: not logged again
        assertTrue(t.read(listOf(prep("GF-992", "0:00")), 150_000).isEmpty())
    }

    @Test
    fun aPressedOrderIsForgottenAndMoreTimeIsLogged() {
        val t = PrepTracker()
        t.read(listOf(prep("GF-1", "5:00"), prep("GF-2", "8:00")), 0)
        // GF-1 reached the Ready tab: no look due for it any more
        t.left(listOf("GF-1"))
        assertEquals(setOf("GF-2"), t.known())
        // more time for GF-2: logged again
        assertEquals(listOf("GF-2"), t.read(listOf(prep("GF-2", "9:00")), 60_000).map { it.gf })
        // the whole list on screen without GF-2: it left
        t.complete(emptyList())
        assertTrue(t.known().isEmpty())
    }

    @Test
    fun aLookAfterTheTimeRanOutCountsEvenIfTheOrderWasGone() {
        val t = PrepTracker()
        t.read(listOf(prep("GF-3", "0:30")), 0)
        assertTrue(t.runOutUnseen(40_000, 3_000))
        t.checked(40_000)
        assertFalse(t.runOutUnseen(60_000, 3_000))
    }

    @Test
    fun memoryComesBackAfterARestart() {
        val t = PrepTracker()
        t.seed(
            listOf(
                Record("a", 0, RecordKind.SEEN, listOf(prep("GF-4", "5:00"), prep("GF-5", "6:00"))),
                Record("b", 300_000, RecordKind.PREPARING, listOf(prep("GF-4", "0:00")), uri = "content://x"),
                Record("c", 330_000, RecordKind.READY, listOf(Item("GF-5", ObsType.READY)), uri = "content://y"),
            ),
        )
        assertEquals(setOf("GF-4"), t.known())
        assertFalse(t.needsOverdueShot("GF-4"))
    }
}
