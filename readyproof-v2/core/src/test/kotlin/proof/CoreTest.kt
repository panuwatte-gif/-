package proof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTest {
    private val date = "2026-10-08"
    @Test fun thaiHeaderFromReferenceHasSeventySevenWithoutExplicitTotal() {
        val h = HeaderParser.fromText(listOf("ยอดขายสุทธิ", "เสร็จสมบูรณ์", "77", "ยกเลิก", "0", "คำสั่งซื้อ"))
        assertEquals(Header(77,77,0),h)
        assertTrue(h.consistent)
    }
    @Test fun absentHeaderIsNotZeroOrders() {
        assertEquals(null,HeaderParser.fromText(listOf("History","GF-898")).total)
    }
    @Test fun oneDelayedAmongMultipleCardsAndReadyNeverCreatesCase() {
        val ready = listOf(Ready("a", date, "GF-898", 1000, "table", "r.jpg"),
            Ready("b", date, "GF-888", 1000, "table", "r.jpg"))
        val rows = listOf(
            History("h1", date, Card("GF-898", 10, 50, Status.COMPLETED, delayed = true), 2000, "d.jpg"),
            History("h2", date, Card("GF-888", 60, 99, Status.COMPLETED), 2000, "d.jpg"))
        assertEquals(listOf("GF-898"), Logic.match(rows, ready).map { it.history.card.gf })
        assertTrue(Logic.match(emptyList(), ready).isEmpty())
        assertEquals(1, Logic.match(rows, ready).count { it.kind == CaseKind.MATCHED })
    }
    @Test fun repeatedGfStaysDistinctAndAmbiguousIsUnknown() {
        val first = Logic.instance(emptyList(), date, "GF-700", 1000, "x", emptySet())
        assertEquals(first.id, Logic.instance(listOf(first), date, "GF-700", 2000, "x", setOf(first.id)).id)
        val second = Logic.instance(listOf(first), date, "GF-700", 200000, "x", emptySet())
        assertFalse(first.id == second.id)
        val h = History("h", date, Card("GF-700", 1, 20, Status.COMPLETED, delayed = true), 250000, "d.jpg")
        assertEquals(CaseKind.UNKNOWN, Logic.match(listOf(h), listOf(first.copy(image = "a"), second.copy(image = "b"))).single().kind)
    }
    @Test fun yesterdayUsesOrderDateButCaptureTimeIsSeparate() {
        val h = History("h", date, Card("GF-285", 0, 10, Status.COMPLETED, delayed = true), 1791510000000, "d")
        val report = Logic.report("กะเพรา", date, Logic.audit(Header(1, 1, 0), listOf(h), false),
            Logic.match(listOf(h), emptyList()))
        assertTrue(report.contains(date))
        assertTrue(report.contains("GF-285"))
    }
    @Test fun seventySevenAndFailedCapturesCannotPass() {
        val h = (1..9).map { History("$it", date, Card("GF-$it", 1, 9, Status.COMPLETED), 1, "p") }
        assertEquals(68, Logic.audit(Header(77, 70, 7), h, false).missingRows)
        assertFalse(Logic.audit(Header(77, 70, 7), h, false).complete)
        val all = (1..77).map { History("$it", date, Card("GF-$it", 1, 9, Status.COMPLETED), 1,
            if (it == 4) null else "p") }
        assertFalse(Logic.audit(Header(77, 70, 7), all, false).complete)
        assertTrue(Logic.audit(Header(77, 70, 7), all.map { it.copy(image = "p") }, false).complete)
        assertFalse(Logic.audit(Header(77, 70, 7), all.map { it.copy(image = "p") },
            false, headerImage = false).complete)
    }
    @Test fun missingReadyRetriesAndSuccessfulDoesNotRepeat() {
        val cards = listOf(Card("GF-1", 1, 20), Card("GF-2", 21, 40))
        val rows = listOf(Ready("a", date, "GF-1", 1, "", "ready.jpg"), Ready("b", date, "GF-2", 1, "", null))
        assertEquals(listOf("GF-2"), Logic.needCapture(cards, rows).map { it.gf })
    }
    @Test fun historyLockAndPartialShareModel() {
        val gate = SweepGate()
        gate.start(date)
        assertFalse(gate.allows(PageKind.READY))
        assertTrue(gate.allows(PageKind.HISTORY))
        gate.stop()
        assertTrue(gate.allows(PageKind.READY))
        val rows = listOf(History("1", date, Card("GF-1", 1, 20, Status.COMPLETED, delayed = true), 2, "valid.jpg"),
            History("2", date, Card("GF-2", 1, 20, Status.COMPLETED, delayed = true), 2, null))
        assertEquals(1, Logic.match(rows, emptyList()).size)
    }
}
