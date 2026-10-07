package io.github.panuwattegif.readyproof.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureFeedbackTest {
    @Test fun mixedHistoryScreenshotNamesOnlyTheDelayedOrder() {
        val rows = listOf("GF-958", "GF-242", "GF-269", "GF-399", "GF-346")
            .map { Item(it, ObsType.DONE, doneAt = "18:00", historyDate = "2026-10-07") }
        val shot = Record("history", 1791370800000L, RecordKind.DELAY,
            rows + Item("GF-269", ObsType.DELAY, delayMin = 4, doneAt = "18:00", historyDate = "2026-10-07"), uri = "content://history")
        assertEquals("ล่าช้า GF-269", CaptureFeedback.status(shot))
        assertEquals(listOf("GF-269"), CaptureFeedback.delayedGfs(shot))
        val report = ReportBuilder.build(listOf(shot), LocalDate.parse("2026-10-07"), ZoneId.of("Asia/Bangkok"), Config.DEFAULT)
        assertEquals(1, report.delayed)
        assertEquals(listOf("GF-269"), report.cases.map { it.gf })
    }

    @Test fun readyOnlyIsSilentAndCreatesNoDelayedCase() {
        val shot = Record("ready", 1791370800000L, RecordKind.READY,
            listOf(Item("GF-958", ObsType.READY)), uri = "content://ready")
        assertNull(CaptureFeedback.notification(shot))
        val report = ReportBuilder.build(listOf(shot), LocalDate.parse("2026-10-07"), ZoneId.of("Asia/Bangkok"), Config.DEFAULT)
        assertEquals(0, report.delayed)
        assertEquals(emptyList(), report.cases)
    }

    @Test fun normalHistoryIsSilentEvenWhenItsRecordKindWasDelay() {
        val shot = Record("normal", 0, RecordKind.DELAY, listOf(Item("GF-958", ObsType.DONE)))
        assertNull(CaptureFeedback.notification(shot))
        assertEquals("เก็บภาพ History แล้ว", CaptureFeedback.status(shot))
    }
}
