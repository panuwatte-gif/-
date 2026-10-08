package io.github.panuwattegif.readyproof.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DedicatedMonitorTest {
    private val cfg = Config.DEFAULT
    @Test fun manuallyOpenedHistoryWithReadyLabelIsNeverTakenOver() {
        assertEquals(DedicatedMonitor.Action.IDLE, DedicatedMonitor.decide(listOf(Trees.historyTabEn()), cfg, false))
        assertEquals(true, DedicatedMonitor.historyOpen(listOf(Trees.historyTabEn()), cfg))
    }
    @Test fun thaiTerminalRowsProtectHistoryWhenSelectionIsOmitted() {
        assertEquals(DedicatedMonitor.Action.IDLE, DedicatedMonitor.decide(listOf(Trees.historyTab()), cfg, false))
    }
    @Test fun emptySelectedHistoryIsStillProtected() {
        val screen = Trees.box(Trees.n("History", selected = true), Trees.n("Ready"), Trees.n("No orders"))
        assertEquals(DedicatedMonitor.Action.IDLE, DedicatedMonitor.decide(listOf(screen), cfg, false))
    }
    @Test fun readyAndPreparingRemainEligibleForNormalMonitoring() {
        assertEquals(false, DedicatedMonitor.historyOpen(listOf(Trees.readyTab(true)), cfg))
        assertEquals(false, DedicatedMonitor.historyOpen(listOf(Trees.preparingTab()), cfg))
    }
    @Test fun readyOnProofPhoneIsWatchedWithoutAnyLocalReadyTap() {
        assertEquals(DedicatedMonitor.Action.WATCH_READY, DedicatedMonitor.decide(listOf(Trees.readyTab(true)), cfg, false))
    }
    @Test fun preparingReturnsToReadyRegardlessOfWhereOrdersWereAccepted() {
        assertEquals(DedicatedMonitor.Action.OPEN_READY, DedicatedMonitor.decide(listOf(Trees.preparingTab()), cfg, false))
    }
    @Test fun homeCanNavigateOrdersBeforeReady() {
        val home = Trees.box(Trees.n("Home"), Trees.n("Orders", clickable = true))
        assertEquals(DedicatedMonitor.Action.OPEN_ORDERS, DedicatedMonitor.decide(listOf(home), cfg, false))
    }
    @Test fun omittedTabSelectionDoesNotBecomeReadyProof() {
        assertEquals(DedicatedMonitor.Action.OPEN_READY, DedicatedMonitor.decide(listOf(Trees.readyTab(false)), cfg, false))
    }
    @Test fun closingHistoryAndCaptureAreNeverInterrupted() {
        assertEquals(DedicatedMonitor.Action.IDLE, DedicatedMonitor.decide(listOf(Trees.preparingTab()), cfg, true))
    }
    @Test fun aReadyCountdownDoesNotAuthorizeClickingFoodReady() {
        val screen = Trees.box(Trees.n("GF-271"), Trees.n("Ready in: 5:00 min"))
        assertEquals(DedicatedMonitor.Action.WAIT_FOR_GRAB, DedicatedMonitor.decide(listOf(screen), cfg, false))
    }
    @Test fun missingRootOrLoginWaitsRatherThanApprovingDialogs() {
        assertEquals(DedicatedMonitor.Action.WAIT_FOR_GRAB, DedicatedMonitor.decide(emptyList(), cfg, false))
        assertEquals(DedicatedMonitor.Action.WAIT_FOR_GRAB, DedicatedMonitor.decide(listOf(Trees.n("Sign in")), cfg, false))
    }
    @Test fun disabledCaptureLeavesNavigationAlone() {
        val screen = listOf(Trees.preparingTab())
        assertEquals(DedicatedMonitor.Action.IDLE, DedicatedMonitor.decide(screen, cfg.copy(enabled = false), false))
    }
}
