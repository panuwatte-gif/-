package io.github.panuwattegif.readyproof.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class HistoryScrollRetryTest {
    @Test fun firstFailedScrollDoesNotFinishHistory() {
        val progress = HistoryScrollRetry()
        assertTrue(progress.shouldRetry(false, true, true))
        assertTrue(progress.shouldRetry(false, true, true))
        assertFalse(progress.shouldRetry(false, true, true))
        progress.shouldRetry(true, true, true)
        assertTrue(progress.shouldRetry(false, true, true))
    }
    @Test fun unreadableOrMissingListIsNeverAnEndMarker() {
        val progress = HistoryScrollRetry()
        repeat(10) {
            assertTrue(progress.shouldRetry(false, false, false))
            assertTrue(progress.shouldRetry(false, true, false))
        }
        assertTrue(progress.shouldRetry(false, true, true))
    }
    @Test fun readyPollingDoesNotRecaptureTheSameOrderEveryTwoMinutes() {
        val deduper = Deduper()
        val ready = Item("GF-958", ObsType.READY)
        val cfg = Config.DEFAULT.copy(readyRepeatMinutes = 90)
        deduper.mark(listOf(Deduper.keyOf(ready)!!), 1_000L)
        repeat(44) { assertTrue(deduper.fresh(listOf(ready), 1_000L + (it + 1) * 120_000L, cfg).isEmpty()) }
        val next = Item("GF-242", ObsType.READY)
        assertEquals(listOf(next), deduper.fresh(listOf(ready, next), 121_000L, cfg))
        deduper.forget(listOf(Deduper.keyOf(ready)!!))
        assertEquals(listOf(ready), deduper.fresh(listOf(ready), 121_000L, cfg))
    }
}
