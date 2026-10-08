package io.github.panuwattegif.readyproofclean.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleanCoreTest {
    @Test fun historyTotal77CannotFinishAt9() {
        assertFalse(SweepPolicy.complete(77, 9, 9, true, true))
        assertTrue(SweepPolicy.complete(77, 77, 77, true, false))
    }

    @Test fun dateBoundaryCanFinishWhenHeaderUnavailable() {
        assertFalse(SweepPolicy.complete(null, 12, 12, true, false))
        assertTrue(SweepPolicy.complete(null, 12, 12, true, true))
    }

    @Test fun parsesDelayAndExplicitHistoryDates() {
        assertEquals(4, Parsers.delayMinutes("Delayed by 4 mins", listOf("Delayed by")))
        assertEquals(LocalDate.of(2026, 10, 8), HistoryDates.parse("Today, 08 Oct 2026", LocalDate.of(2026, 10, 9)))
        assertEquals(LocalDate.of(2026, 10, 8), HistoryDates.parse("08 ต.ค. 2569", LocalDate.of(2026, 10, 9)))
    }

    @Test fun parsesHistoryHeaderTotal() {
        val x = HistoryTotalsParser.parse(listOf("Completed 75", "Cancelled 2", "Total 77"))
        assertEquals(75, x?.completed)
        assertEquals(2, x?.cancelled)
        assertEquals(77, x?.total)
    }

    @Test fun gfExtractorIsExact() {
        val g = Config.DEFAULT.gfExtractor()
        assertEquals(listOf("GF-285"), g.extract("Order GF-285"))
        assertTrue(g.extract("GF-28x5").isEmpty())
    }
}
