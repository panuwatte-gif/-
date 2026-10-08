package io.github.panuwattegif.readyproof.core

/** A failed accessibility action is not evidence that the History list reached its end. */
class HistoryScrollRetry {
    private var failures = 0
    fun reset() { failures = 0 }
    fun shouldRetry(moved: Boolean, containerFound: Boolean, rowsRead: Boolean): Boolean {
        if (moved) { reset(); return false }
        if (!containerFound || !rowsRead) return true // bounded by the pass watchdog
        failures++
        return failures < 3
    }
}
