package io.github.panuwattegif.readyproof.core

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClosingHistoryTest {
    private val cfg = Config.DEFAULT
    private fun page(tab: ClosingTab, badge: String? = null, message: String? = null,
                     gf: String? = null, selection: Boolean = true): List<UiNode> = listOf(Trees.box(
        Trees.box(Trees.tab("Ready", if (tab == ClosingTab.READY) badge else "4", selection && tab == ClosingTab.READY),
            Trees.tab("Preparing", if (tab == ClosingTab.PREPARING) badge else "4", selection && tab == ClosingTab.PREPARING),
            Trees.tab("History", null)),
        Trees.list(*listOfNotNull(message?.let { Trees.n(it) }, gf?.let { Trees.n(it) }).toTypedArray()),
    ))
    private fun inspect(roots: List<UiNode>, tab: ClosingTab = ClosingTab.READY, clicked: Boolean = true) =
        ClosingQueueAnalyzer.inspect(roots, cfg, tab, clicked)

    @Test fun startsAt1900WithoutWeekdayExceptions() {
        assertFalse(ClosingHistoryGate.isDue(LocalTime.of(18, 59, 59)))
        assertTrue(ClosingHistoryGate.isDue(LocalTime.of(19, 0)))
        assertTrue(ClosingHistoryGate.isDue(LocalTime.of(23, 59)))
    }
    @Test fun missingTreeOrMissingGfNeverProvesEmpty() {
        assertEquals(QueueState.UNKNOWN, inspect(emptyList()))
        assertEquals(QueueState.UNKNOWN, inspect(page(ClosingTab.READY)))
    }
    @Test fun explicitEmptyAndSeparateOwnZeroBadgeAreAccepted() {
        assertEquals(QueueState.EMPTY, inspect(page(ClosingTab.READY, message = "No orders")))
        assertEquals(QueueState.EMPTY, inspect(page(ClosingTab.READY, badge = "0")))
    }
    @Test fun combinedBadgeAndThaiEmptyTextAreSupported() {
        val r = Trees.box(Trees.tab("พร้อมจัดส่ง (0)", null, true), Trees.n("ไม่มีคำสั่งซื้อ"))
        assertEquals(QueueState.EMPTY, inspect(listOf(r)))
    }
    @Test fun siblingQueueZeroCannotOverrideTheExpectedQueuesCount() {
        assertEquals(QueueState.BUSY, inspect(page(ClosingTab.READY, badge = "2")))
        assertEquals(QueueState.UNKNOWN, inspect(page(ClosingTab.READY))) // Preparing's 4 is not borrowed.
    }
    @Test fun positiveCountOrGfBeatsStaleEmptyText() {
        assertEquals(QueueState.BUSY, inspect(page(ClosingTab.READY, "2", "No orders")))
        assertEquals(QueueState.BUSY, inspect(page(ClosingTab.READY, "0", "No orders", "GF-271")))
    }
    @Test fun loadingAndErrorDoNotCertifyZero() {
        assertEquals(QueueState.UNKNOWN, inspect(page(ClosingTab.READY, "0", "Loading")))
        assertEquals(QueueState.UNKNOWN, inspect(page(ClosingTab.READY, "0", "Connection error. Try again")))
    }
    @Test fun wrongSelectedTabCannotBeEmptyEvenAfterSuccessfulClick() {
        assertEquals(QueueState.UNKNOWN, inspect(page(ClosingTab.PREPARING, "0", "No orders")))
    }
    @Test fun omittedSelectionRequiresNavigationAndPositiveEmptyEvidence() {
        val p = page(ClosingTab.READY, message = "ไม่มีออเดอร์", selection = false)
        assertEquals(QueueState.UNKNOWN, inspect(p, clicked = false))
        assertEquals(QueueState.EMPTY, inspect(p, clicked = true))
        assertEquals(QueueState.UNKNOWN, inspect(page(ClosingTab.READY, selection = false)))
    }
    @Test fun oneEmptyObservationNeverOpensHistory() {
        val g = ClosingHistoryGate(0)
        assertEquals(ClosingHistoryGate.Result.WAIT, g.observe(QueueState.EMPTY, 0))
        assertEquals(ClosingHistoryGate.Result.WAIT, g.observe(QueueState.EMPTY, 1_499))
        assertEquals(ClosingHistoryGate.Result.NEXT_TAB, g.observe(QueueState.EMPTY, 1_500))
        assertEquals(ClosingTab.PREPARING, g.tab)
    }
    @Test fun bothQueuesMustBeEmptyAndReadyIsRecheckedBeforeHistory() {
        val g = ClosingHistoryGate(0)
        g.observe(QueueState.EMPTY, 0)
        assertEquals(ClosingHistoryGate.Result.NEXT_TAB, g.observe(QueueState.EMPTY, 1_500))
        g.observe(QueueState.EMPTY, 3_000)
        assertEquals(ClosingHistoryGate.Result.NEXT_TAB, g.observe(QueueState.EMPTY, 4_500))
        assertEquals(ClosingTab.READY, g.tab)
        g.observe(QueueState.EMPTY, 6_000)
        assertEquals(ClosingHistoryGate.Result.OPEN_HISTORY, g.observe(QueueState.EMPTY, 7_500))
    }
    @Test fun pendingPreparingOrNewReadyOrderDefersHistory() {
        val preparing = ClosingHistoryGate(0)
        preparing.observe(QueueState.EMPTY, 0)
        preparing.observe(QueueState.EMPTY, 1_500)
        assertEquals(ClosingHistoryGate.Result.RETRY, preparing.observe(QueueState.BUSY, 3_000))
        val ready = ClosingHistoryGate(0)
        ready.observe(QueueState.EMPTY, 0); ready.observe(QueueState.EMPTY, 1_500)
        ready.observe(QueueState.EMPTY, 3_000); ready.observe(QueueState.EMPTY, 4_500)
        assertEquals(ClosingHistoryGate.Result.RETRY, ready.observe(QueueState.BUSY, 6_000))
    }
    @Test fun unknownResetsStabilityAndEventuallyRetries() {
        val g = ClosingHistoryGate(0)
        g.observe(QueueState.EMPTY, 0)
        assertEquals(ClosingHistoryGate.Result.WAIT, g.observe(QueueState.UNKNOWN, 1_500))
        assertEquals(ClosingHistoryGate.Result.WAIT, g.observe(QueueState.EMPTY, 3_000))
        assertEquals(ClosingHistoryGate.Result.RETRY, g.observe(QueueState.UNKNOWN, 45_000))
    }
}
