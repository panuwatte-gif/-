package io.github.panuwattegif.readyproof.core

/** Repeated reads while capturing are not failed scrolls and cannot prove the list has ended. */
class SweepProgress {
    var repeated: Int = 0
        private set
    private var signature = ""
    private var awaitingMovement = false
    fun scrolled() { awaitingMovement = true }
    fun observe(next: String) {
        if (next.isEmpty()) return
        if (next != signature) repeated = 0
        else if (awaitingMovement) repeated++
        signature = next
        awaitingMovement = false
    }
    fun reset() { signature = ""; repeated = 0; awaitingMovement = false }
}
