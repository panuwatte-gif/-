package io.github.panuwattegif.readyproof.core

import kotlin.test.*

class SweepProgressTest {
    @Test fun pollingAndPartialCapturesCannotEndTheSweep() {
        val progress = SweepProgress()
        repeat(20) { progress.observe("same-first-page") }
        assertEquals(0, progress.repeated)
        progress.scrolled()
        progress.observe("same-first-page")
        assertEquals(1, progress.repeated)
        repeat(20) { progress.observe("same-first-page") }
        assertEquals(1, progress.repeated)
        progress.scrolled()
        progress.observe("next-page")
        assertEquals(0, progress.repeated)
    }

    @Test fun onlyAttemptedScrollsCountAtTheBoundary() {
        val progress = SweepProgress()
        progress.observe("last-page")
        repeat(4) { progress.scrolled(); progress.observe("last-page") }
        assertEquals(4, progress.repeated)
        progress.reset()
        assertEquals(0, progress.repeated)
    }
}
