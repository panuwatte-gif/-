package io.github.panuwattegif.readyproofclean.core

object SweepPolicy {
    fun complete(expectedTotal: Int?, seen: Int, proof: Int, targetSeen: Boolean, olderBoundarySeen: Boolean): Boolean =
        if (expectedTotal != null) {
            expectedTotal > 0 && seen >= expectedTotal && proof >= expectedTotal
        } else {
            targetSeen && olderBoundarySeen && seen > 0 && proof >= seen
        }
}
