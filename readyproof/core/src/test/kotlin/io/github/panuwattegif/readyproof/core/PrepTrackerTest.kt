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
    fun aRedCardWhosePhotoFailedIsLookedAtAgainUntilItsPhotoIsSaved() {
        val t = PrepTracker()
        t.read(listOf(prep("GF-7", "0:10")), 0)
        assertTrue(t.runOutUnseen(13_000, 3_000))
        // the look finds it red, but the photo is not saved (capture failed / not on screen)
        t.read(listOf(prep("GF-7", "0:00")), 14_000)
        t.missedOverdue(listOf("GF-7"))
        t.checked(15_000, setOf("GF-7"))
        // seen and seen red, but no photo: the job is not done
        assertTrue(t.needsOverdueShot("GF-7"))
        assertTrue(t.runOutUnseen(30_000, 3_000))
        // second try saves it: done, no more looks
        t.shotOverdue(listOf("GF-7"), 31_000)
        assertFalse(t.runOutUnseen(60_000, 3_000))
        assertFalse(t.needsOverdueShot("GF-7"))
    }

    @Test
    fun triesStopAfterThreeMissesOrWhenTheCardIsGone() {
        val t = PrepTracker()
        t.read(listOf(prep("GF-8", "0:00"), prep("GF-9", "0:00")), 0)
        repeat(PrepTracker.MAX_MISSES) { i ->
            t.missedOverdue(listOf("GF-8"))
            t.checked(10_000L * (i + 1), setOf("GF-8", "GF-9"))
        }
        t.shotOverdue(listOf("GF-9"), 1_000)
        // three looks without a photo: no endless switching
        assertFalse(t.runOutUnseen(100_000, 3_000))
        // a red card missed once, then not on the tab at the next look (pressed): no more looks
        val u = PrepTracker()
        u.read(listOf(prep("GF-10", "0:00")), 0)
        u.missedOverdue(listOf("GF-10"))
        u.checked(5_000, setOf("GF-10"))
        assertTrue(u.runOutUnseen(20_000, 3_000))
        u.checked(21_000, emptySet())
        assertFalse(u.runOutUnseen(40_000, 3_000))
    }

    @Test
    fun aRedPhotoKeptWhileTheScreenMovedCountsButOneCheckedPhotoMayFollow() {
        val t = PrepTracker()
        t.read(listOf(prep("GF-11", "0:00")), 0)
        t.backupOverdue(listOf("GF-11"), 1_000)
        assertFalse(t.runOutUnseen(60_000, 3_000))
        // only partly on screen next time: no new photo; completely on screen: one checked photo
        assertFalse(t.needsOverdueShot("GF-11", whole = false))
        assertTrue(t.needsOverdueShot("GF-11", whole = true))
        t.shotOverdue(listOf("GF-11"), 70_000)
        assertFalse(t.needsOverdueShot("GF-11", whole = true))
    }

    @Test
    fun memoryComesBackAfterARestart() {
        val t = PrepTracker()
        t.seed(
            listOf(
                Record("a", 0, RecordKind.SEEN, listOf(prep("GF-4", "5:00"), prep("GF-5", "6:00"))),
                Record("b", 300_000, RecordKind.PREPARING, listOf(prep("GF-4", "0:00")), uri = "content://x"),
                Record("c", 330_000, RecordKind.READY, listOf(Item("GF-5", ObsType.READY)), uri = "content://y"),
                // red seen, its photo kept while the screen moved
                Record("d", 340_000, RecordKind.SEEN, listOf(prep("GF-6", "0:00"))),
                Record("e", 341_000, RecordKind.PREPARING, listOf(prep("GF-6", "0:00")), uri = "content://z", note = PrepTracker.BACKUP_NOTE),
                // red seen, no photo yet
                Record("f", 350_000, RecordKind.SEEN, listOf(prep("GF-12", "0:00"))),
            ),
        )
        assertEquals(setOf("GF-4", "GF-6", "GF-12"), t.known())
        assertFalse(t.needsOverdueShot("GF-4"))
        assertFalse(t.needsOverdueShot("GF-6", whole = false))
        assertTrue(t.needsOverdueShot("GF-12"))
    }

    @Test
    fun anOrderGoneFromTheWholeListIsReportedWithItsLastSighting() {
        val t = PrepTracker()
        t.read(listOf(prep("GF-884", "13:35"), prep("GF-528", "7:46")), 0)
        // the countdown moves by less than 30 s: not logged, but remembered as the last sighting
        t.read(listOf(prep("GF-884", "0:34"), prep("GF-528", "0:20")), 783_000)
        assertEquals(listOf(PrepTracker.Gone("GF-884", 783_000, "0:34")), t.complete(listOf("GF-528")))
        assertEquals(setOf("GF-528"), t.known())
        // nothing more left
        assertTrue(t.complete(listOf("GF-528")).isEmpty())
        // after a restart, an order noted as gone is not followed again
        val u = PrepTracker()
        u.seed(listOf(
            Record("a", 0, RecordKind.SEEN, listOf(prep("GF-884", "13:35"))),
            Record("b", 824_000, RecordKind.SEEN, listOf(Item("GF-884", ObsType.LEFT_PREP, countdown = "0:34", prevAt = 783_000))),
        ))
        assertTrue(u.known().isEmpty())
    }
}
