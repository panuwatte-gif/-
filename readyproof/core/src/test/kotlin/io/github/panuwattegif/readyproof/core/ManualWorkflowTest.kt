package io.github.panuwattegif.readyproof.core

import kotlin.test.*

class ManualWorkflowTest {
    @Test fun readyDoesNotRepeatByTimeButFailedCaptureRetries() {
        val d = Deduper()
        val item = Item("GF-269", ObsType.READY)
        val cfg = Config.DEFAULT.copy(readyRepeatMinutes = ManualWorkflow.readyRepeatMinutes)
        val key = Deduper.keyOf(item)!!
        d.mark(listOf(key), 1000)
        assertTrue(d.fresh(listOf(item), 1000 + 12 * 3600_000L, cfg).isEmpty())
        assertEquals(listOf(Item("GF-270", ObsType.READY)), d.fresh(listOf(Item("GF-270", ObsType.READY)), 2000, cfg))
        d.forget(listOf(key))
        assertEquals(listOf(item), d.fresh(listOf(item), 2000, cfg))
        d.clear()
        assertEquals(listOf(item), d.fresh(listOf(item), 3000, cfg))
    }
    @Test fun unattendedNavigationAndUploadAreDisabled() {
        assertFalse(ManualWorkflow.autoNavigation)
        assertFalse(ManualWorkflow.autoUpload)
    }
    @Test fun headerCountsAreIndependentOfScreenshotCount() {
        assertEquals(HistoryTotals(75, 1, 76), HistoryTotalsParser.parse(listOf("Total orders 76", "Completed 75", "Cancelled 1")))
        assertEquals(HistoryTotals(75, 1, 76), HistoryTotalsParser.parse(listOf("สำเร็จ 75 รายการ", "ยกเลิก 1 รายการ")))
        assertNull(HistoryTotalsParser.parse(listOf("GF-269", "Completed at 1:35 PM", "Delayed by 5 mins")))
        assertFalse(HistoryTotalsParser.parse(listOf("Total 90", "Completed 75", "Cancelled 1"))!!.consistent)
    }
    @Test fun splitHeaderLabelsAndNumbersAreReadWithoutBorrowingOrderTimes() {
        val header = Trees.box(Trees.box(Trees.n("Completed"), Trees.n("77")), Trees.box(Trees.n("Cancelled"), Trees.n("0")))
        assertEquals(HistoryTotals(77, 0, 77), HistoryTotalsParser.parseRoots(listOf(header), Config.DEFAULT))
        assertNull(HistoryTotalsParser.parseRoots(listOf(Trees.historyTabEn()), Config.DEFAULT))
    }
}
