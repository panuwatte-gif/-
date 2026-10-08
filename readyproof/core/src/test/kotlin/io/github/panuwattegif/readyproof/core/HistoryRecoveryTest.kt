package io.github.panuwattegif.readyproof.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class HistoryRecoveryTest {
    private val yesterday = LocalDate.of(2026, 10, 8)
    private val zone = ZoneId.of("Asia/Bangkok")
    private fun report(rs: List<Record>, day: LocalDate) = ReportBuilder.build(rs, day, zone, Config.DEFAULT)

    @Test fun seventySevenOrdersCapturedTodayRemainOnTheirBusinessDate() {
        val captured = TimeResolve.toMillis(yesterday.plusDays(1).atTime(9, 0), zone)
        val rows = (1..77).map { Item("GF-${it.toString().padStart(3, '0')}", ObsType.DONE, doneAt = "18:00") }
        val record = Record("history", captured, RecordKind.HISTORY, rows, uri = "content://history", historyDate = yesterday.toString())
        val r = report(listOf(record), yesterday)
        assertEquals(77, r.completedSeen)
        assertEquals(0, report(listOf(record), yesterday.plusDays(1)).completedSeen)
        assertEquals(captured, record.t) // Capture time is never changed to manufacture evidence.
        assertEquals(0, r.delayed)
    }

    @Test fun explicitDateAlsoAppliesWhenTerminalClockIsUnknown() {
        val record = Record("unknown-clock", TimeResolve.toMillis(yesterday.plusDays(1).atTime(9, 0), zone),
            RecordKind.SEEN, listOf(Item("GF-285", ObsType.DELAY)), historyDate = yesterday.toString())
        assertEquals(1, report(listOf(record), yesterday).delayed)
        assertEquals(0, report(listOf(record), yesterday.plusDays(1)).delayed)
        assertEquals("UNKNOWN_INSTANCE", report(listOf(record), yesterday).cases.single().matchStatus)
    }

    @Test fun readyRecordCannotBecomeDelayedEvenWithCorruptTerminalMetadata() {
        val record = Record("bad-ready", TimeResolve.toMillis(yesterday.atTime(18, 0), zone), RecordKind.READY,
            listOf(Item("GF-285", ObsType.READY), Item("GF-285", ObsType.DELAY, doneAt = "18:00", delayMin = 4)),
            uri = "content://ready", historyDate = yesterday.toString())
        assertEquals(0, report(listOf(record), yesterday).delayed)
    }

    @Test fun selectedReadyRejectsTerminalStringsForAnalysisAndImageValidation() {
        val root = UiNode(left = 0, top = 0, right = 500, bottom = 900)
        root.add(UiNode(text = "Ready", selected = true))
        val list = UiNode(collection = true, left = 0, top = 80, right = 500, bottom = 900)
        root.add(list)
        val card = UiNode(left = 0, top = 100, right = 500, bottom = 300)
        list.add(card)
        card.add(UiNode(text = "GF-285", left = 10, top = 100, right = 200, bottom = 140))
        card.add(UiNode(text = "Completed at 18:00 Delayed by 4 mins", left = 10, top = 150, right = 490, bottom = 220))
        val analysis = ScreenAnalyzer.analyze(listOf(root), Config.DEFAULT, allowUnknownDelayed = true)
        assertFalse(analysis.items.any { it.type == ObsType.DELAY })
        assertTrue(ProofValidation.targets(RecordKind.DELAY, listOf(Item("GF-285", ObsType.DELAY, doneAt = "18:00")),
            listOf(root), Config.DEFAULT).isEmpty())
    }

    @Test fun splitTerminalClockAndExplicitNumericDatesAreReadWithoutGuessing() {
        assertEquals("18:15", Parsers.doneAtLines(listOf("GF-285", "Completed", "6:15 PM"), Config.DEFAULT.doneAny))
        assertNull(Parsers.doneAtLines(listOf("Completed", "Price 223.00", "18:15"), Config.DEFAULT.doneAny))
        assertEquals(yesterday, HistoryDates.parse("08/10/2026", yesterday.plusDays(1)))
        assertEquals(yesterday, HistoryDates.parse("08/10/2569", yesterday.plusDays(1)))
        assertNull(HistoryDates.parse("08/10", yesterday.plusDays(1)))
    }
}
