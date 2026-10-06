package io.github.panuwattegif.readyproof.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class EndToEndTest {
    private val day = LocalDate.of(2026, 10, 7)
    private val zone = ZoneId.of("Asia/Bangkok")
    private fun record(id: String, hour: Int, minute: Int, vararg items: Item,
        image: Boolean = true, shop: String? = Shop.KAPRAO.id, dated: Boolean = true) = Record(id,
        TimeResolve.toMillis(day.atTime(hour, minute), zone), RecordKind.HISTORY, items.toList(),
        uri = if (image) "content://images/$id" else null, shopId = shop,
        historyDate = if (dated) day.toString() else null)
    private fun report(rs: List<Record>, end: Boolean = true) =
        ReportBuilder.build(rs, day, zone, Config.DEFAULT, Shop.KAPRAO.id, end)

    @Test fun coverageIsPerOrderAndIncompleteDaysStillExportEveryDelayedCase() {
        val rs = listOf(
            record("ready-seen", 10, 0, Item("GF-001", ObsType.READY), Item("GF-002", ObsType.READY), image = false),
            record("one-valid", 10, 0, Item("GF-001", ObsType.READY)),
            record("seen", 20, 0, Item("GF-001", ObsType.DELAY, doneAt = "10:20"),
                Item("GF-002", ObsType.DELAY, doneAt = "10:30"),
                Item("GF-003", ObsType.CANCELLED, doneAt = "11:00"), image = false),
            record("valid-history", 20, 1, Item("GF-001", ObsType.DELAY, doneAt = "10:20"),
                Item("GF-002", ObsType.DELAY, doneAt = "10:30")))
        val r = report(rs)
        assertEquals(3, r.historyOrders) // two completed plus one cancelled, not four screenshots
        assertEquals(2, r.delayed)
        assertEquals(1, r.withEvidence.size)
        assertEquals(1, r.actualDelayed)
        assertEquals(listOf("GF-002@10:30"), r.pendingReadyGfs)
        assertEquals(listOf("GF-003@11:00"), r.missingHistoryInstances)
        assertFalse(r.complete)
        assertEquals(2, ReportText.csv(r, zone).lines().count { it.startsWith(day.toString()) })
        assertTrue(ReportText.summary(r, zone).contains("INCOMPLETE"))
        assertEquals(1, r.sets().size)
        assertNotNull(r.withoutEvidence.single().delayShot) // unmatched valid DELAY remains shareable
    }

    @Test fun sameGfMustNotBorrowProofAcrossTerminalBoundariesOrShops() {
        val r = report(listOf(
            record("earlier-ready", 10, 0, Item("GF-009", ObsType.READY)),
            record("other-shop", 15, 0, Item("GF-009", ObsType.READY), shop = Shop.DAUGHTER.id),
            record("legacy", 15, 1, Item("GF-009", ObsType.READY), shop = null),
            record("terminals", 20, 0, Item("GF-009", ObsType.DONE, doneAt = "10:20"),
                Item("GF-009", ObsType.DELAY, doneAt = "15:30"))))
        assertEquals(2, r.historyOrders)
        assertEquals(1, r.delayed)
        assertFalse(r.cases.single().hasEvidence)
        assertEquals(Shop.KAPRAO.id, r.shopId)
    }

    @Test fun unknownClockNeverMatchesByGfAndProofAfterFinishNeverCounts() {
        val unknown = report(listOf(record("ready", 10, 0, Item("GF-008", ObsType.READY)),
            record("unknown", 20, 0, Item("GF-008", ObsType.DELAY))))
        assertEquals("UNKNOWN_INSTANCE", unknown.cases.single().matchStatus)
        assertFalse(unknown.cases.single().hasEvidence)
        assertFalse(unknown.complete)
        val after = report(listOf(record("ready", 10, 21, Item("GF-008", ObsType.READY)),
            record("history", 20, 0, Item("GF-008", ObsType.DELAY, doneAt = "10:20"))))
        assertFalse(after.cases.single().hasEvidence)
    }

    @Test fun completeRequiresImagesEndOfListAndExplicitDate() {
        val rs = listOf(record("ready", 10, 0, Item("GF-001", ObsType.READY)),
            record("all", 20, 0, Item("GF-001", ObsType.DONE, doneAt = "11:00"),
            Item("GF-002", ObsType.CANCELLED, doneAt = "12:00")))
        assertTrue(report(rs).complete)
        assertFalse(report(rs, end = false).complete)
        assertFalse(report(rs.map { it.copy(historyDate = null) }).complete)
        assertEquals(day, HistoryDates.parse("วันนี้", day))
        assertNull(HistoryDates.parse("19:15", day))
        assertEquals(rs.last(), RecordCodec.decode(RecordCodec.encode(rs.last())))
    }

    private fun screen(vararg rows: Pair<String, String>, ready: Boolean = false, shifted: Boolean = false): UiNode {
        val root = UiNode(left = 0, top = 0, right = 500, bottom = 900)
        root.add(UiNode(text = if (ready) "Ready" else "History", selected = true, left = 0, top = 0, right = 200, bottom = 80))
        val list = UiNode(collection = true, left = 0, top = 80, right = 500, bottom = 900)
        root.add(list)
        rows.forEachIndexed { index, row ->
            val top = 100 + index * 200 + if (shifted) 20 else 0
            val card = UiNode(left = 0, top = top, right = 500, bottom = top + 190)
            card.add(UiNode(text = row.first, left = 10, top = top, right = 150, bottom = top + 40))
            card.add(UiNode(text = row.second, left = 10, top = top + 50, right = 490, bottom = top + 150))
            list.add(card)
        }
        return root
    }

    @Test fun bitmapValidationKeepsValidSubsetAndRejectsMovedOrMismatchedRows() {
        val targets = listOf(Item("GF-001", ObsType.DELAY, doneAt = "11:00"), Item("GF-002", ObsType.DELAY, doneAt = "12:00"))
        val before = ProofValidation.targets(RecordKind.HISTORY, targets, listOf(screen(
            "GF-001" to "Completed at 11:00 AM Delayed by 3 mins", "GF-002" to "Completed at 12:00 PM Delayed by 4 mins")), Config.DEFAULT)
        assertEquals(2, before.size)
        val after = ProofValidation.targets(RecordKind.HISTORY, targets, listOf(screen(
            "GF-001" to "Completed at 11:00 AM Delayed by 3 mins")), Config.DEFAULT)
        assertEquals(listOf(targets.first()), ProofValidation.stableSubset(before, after))
        val moved = ProofValidation.targets(RecordKind.HISTORY, targets, listOf(screen(
            "GF-001" to "Completed at 11:00 AM Delayed by 3 mins", shifted = true)), Config.DEFAULT)
        assertTrue(ProofValidation.stableSubset(before, moved).isEmpty())
        assertTrue(ProofValidation.targets(RecordKind.HISTORY, targets, listOf(screen(
            "GF-001" to "Completed at 11:00 AM", "GF-002" to "Completed at 11:00 AM Delayed by 4 mins")), Config.DEFAULT).isEmpty())
        assertTrue(ProofValidation.targets(RecordKind.READY, listOf(Item("GF-001", ObsType.READY)), listOf(screen(
            "GF-001" to "Completed at 11:00 AM")), Config.DEFAULT).isEmpty())
    }

    @Test fun dateBoundarySeparatesTodayFromYesterdayWithinOneScreenshot() {
        val root = screen("GF-001" to "Completed at 11:00 AM", "GF-002" to "Completed at 12:00 PM")
        root.add(UiNode(text = "Today", top = 80))
        root.add(UiNode(text = "Yesterday", top = 290))
        val analysis = ScreenAnalyzer.analyze(listOf(root), Config.DEFAULT)
        val (items, lastHeader) = HistoryDates.assign(analysis.items, analysis, listOf(root), day, null)
        assertEquals(day.toString(), items.first { it.gf == "GF-001" }.historyDate)
        assertEquals(day.minusDays(1).toString(), items.first { it.gf == "GF-002" }.historyDate)
        assertEquals(day.minusDays(1), lastHeader)
    }

    @Test fun manualImagesOnlyProvideReadyEvidenceWhenTheReadyStateIsValidated() {
        val target = Item("GF-001", ObsType.READY)
        assertEquals(listOf(target), ProofValidation.targets(RecordKind.MANUAL, listOf(target),
            listOf(screen("GF-001" to "Finding a driver...", ready = true)), Config.DEFAULT).map { it.item })
        assertTrue(ProofValidation.targets(RecordKind.MANUAL, listOf(target),
            listOf(screen("GF-001" to "Ready in: 5:00 min")), Config.DEFAULT).isEmpty())
    }
}
