package io.github.panuwattegif.readyproof.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackerTest {
    @Test
    fun eachOrderIsShotOncePerStay() {
        val t = ReadyTracker(goneAfterMs = 15_000)
        assertEquals(listOf("GF-1", "GF-2"), t.seen(listOf("GF-1", "GF-2"), 0))
        t.shot(listOf("GF-1", "GF-2"), 1_000)
        assertTrue(t.pending().isEmpty())
        // still waiting for the rider ten minutes later: no new shot
        assertTrue(t.seen(listOf("GF-1", "GF-2"), 600_000).isEmpty())
    }

    @Test
    fun newOrderIsShotEvenWhileOldOnesAreStillListed() {
        val t = ReadyTracker()
        t.seen(listOf("GF-1", "GF-2"), 0)
        t.shot(listOf("GF-1", "GF-2"), 0)
        assertEquals(listOf("GF-3"), t.seen(listOf("GF-1", "GF-2", "GF-3"), 60_000))
    }

    @Test
    fun orderLeavesOnlyAfterTwoCompleteViews() {
        val t = ReadyTracker(goneAfterMs = 15_000)
        t.complete(listOf("GF-1", "GF-2"), 0)
        t.shot(listOf("GF-1", "GF-2"), 0)
        // one glitchy read without GF-2 is not enough
        t.complete(listOf("GF-1"), 10_000)
        assertEquals(listOf("GF-1"), t.present())
        t.complete(listOf("GF-1", "GF-2"), 12_000)
        assertEquals(listOf("GF-1", "GF-2"), t.present())
        assertTrue(t.pending().isEmpty())
        // really gone (rider picked it up)
        t.complete(listOf("GF-1"), 20_000)
        t.complete(listOf("GF-1"), 40_000)
        assertEquals(listOf("GF-1"), t.present())
        // Grab reuses the number later: a new order, a new shot
        assertEquals(listOf("GF-2"), t.seen(listOf("GF-1", "GF-2"), 3_600_000))
    }

    @Test
    fun failedShotsStayPending() {
        val t = ReadyTracker()
        t.seen(listOf("GF-1"), 0)
        t.failed(listOf("GF-1"))
        assertEquals(listOf("GF-1"), t.pending())
        assertEquals(1, t.stay("GF-1")?.failures)
    }

    @Test
    fun restartRemembersRecentShots() {
        val t = ReadyTracker()
        val now = 10 * 3_600_000L
        t.seed(
            listOf(
                Record("a", now - 20 * 60_000, RecordKind.READY, listOf(Item("GF-1", ObsType.READY)), uri = "content://1"),
                Record("b", now - 3 * 3_600_000, RecordKind.READY, listOf(Item("GF-2", ObsType.READY)), uri = "content://2"),
                Record("c", now - 5 * 60_000, RecordKind.READY, listOf(Item("GF-3", ObsType.READY)), uri = null),
            ),
            now,
        )
        assertEquals(listOf("GF-2", "GF-3"), t.seen(listOf("GF-1", "GF-2", "GF-3"), now))
    }

    @Test
    fun ordersWithoutAnyPhotoGetABackupAndAWarningOnce() {
        val t = ReadyTracker()
        t.seen(listOf("GF-1", "GF-2"), 0)
        t.shot(listOf("GF-1"), 1_000)
        assertTrue(t.needBackup(10_000, 30_000).isEmpty())
        assertEquals(listOf("GF-2"), t.needBackup(31_000, 30_000))
        t.backup(listOf("GF-2"), 31_000)
        assertTrue(t.needBackup(40_000, 30_000).isEmpty())
        // still pending: the app keeps trying for a fully checked photo
        assertEquals(listOf("GF-2"), t.pending())
        // a third order that never gets any photo is warned about once
        t.seen(listOf("GF-3"), 50_000)
        assertTrue(t.unphotographed(100_000, 120_000).isEmpty())
        assertEquals(listOf("GF-3"), t.unphotographed(171_000, 120_000))
        assertTrue(t.unphotographed(300_000, 120_000).isEmpty())
    }

    @Test
    fun restartKnowsBackupPhotosFromFullyCheckedOnes() {
        val t = ReadyTracker()
        val now = 10 * 3_600_000L
        t.seed(
            listOf(
                Record("a", now - 60_000, RecordKind.READY, listOf(Item("GF-1", ObsType.READY)), uri = "content://1", note = ReadyTracker.BACKUP_NOTE),
                Record("b", now - 60_000, RecordKind.READY, listOf(Item("GF-2", ObsType.READY)), uri = "content://2"),
            ),
            now,
        )
        assertEquals(listOf("GF-1"), t.seen(listOf("GF-1", "GF-2"), now))
        assertTrue(t.needBackup(now + 60_000, 30_000).isEmpty())
    }

    @Test
    fun aBurstOfNewOrdersSharesOnePhoto() {
        val t = ReadyTracker()
        t.seen(listOf("GF-1"), 0)
        // GF-1 alone for 1 s: wait for the burst to end (4 s quiet)
        assertEquals(3_000, t.batchHold(listOf("GF-1"), 1_000, 4_000, 12_000))
        t.seen(listOf("GF-1", "GF-2"), 3_000)
        // GF-2 arrived: quiet window restarts from GF-2
        assertEquals(4_000, t.batchHold(listOf("GF-1", "GF-2"), 3_000, 4_000, 12_000))
        assertEquals(0, t.batchHold(listOf("GF-1", "GF-2"), 7_000, 4_000, 12_000))
    }

    @Test
    fun aBurstNeverWaitsLongerThanTheLimit() {
        val t = ReadyTracker()
        for (i in 0..5) t.seen(listOf("GF-$i"), i * 2_500L)
        // a new order every 2.5 s, but GF-0 has waited 12 s: photograph now
        assertEquals(0, t.batchHold((0..5).map { "GF-$it" }, 12_500, 4_000, 12_000))
        // 0 = no waiting at all
        val u = ReadyTracker()
        u.seen(listOf("GF-9"), 0)
        assertEquals(0, u.batchHold(listOf("GF-9"), 100, 4_000, 0))
    }

    @Test
    fun ordersAlreadyPhotographedDoNotHoldANewShot() {
        val t = ReadyTracker()
        t.seen(listOf("GF-1"), 0)
        t.shot(listOf("GF-1"), 500)
        assertEquals(0, t.batchHold(listOf("GF-1"), 1_000, 4_000, 12_000))
    }
}
