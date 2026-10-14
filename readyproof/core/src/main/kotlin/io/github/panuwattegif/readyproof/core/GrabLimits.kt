package io.github.panuwattegif.readyproof.core

/**
 * Evidence sent to Grab must fit what Grab accepted: on 13 Oct 2026 the owner's pack went through
 * with 15 images of 150–300 KB each (3.2 MB in all, at most 2000 px a side) after bigger sends
 * never did. Every image is kept under the smallest of those and every send under that pack.
 */
object GrabLimits {
    const val MAX_IMAGE_BYTES = 250_000
    const val MAX_LONG_SIDE_PX = 1600
    const val MAX_FILES_PER_SEND = 15
    const val MAX_BYTES_PER_SEND = 3_000_000L

    /** Splits files (their sizes, in order) into sends that each stay within the limits. */
    fun batches(sizes: List<Long>): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        var bytes = 0L
        sizes.forEachIndexed { i, size ->
            if (cur.isNotEmpty() && (cur.size >= MAX_FILES_PER_SEND || bytes + size > MAX_BYTES_PER_SEND)) {
                out += cur
                cur = ArrayList()
                bytes = 0L
            }
            cur += i
            bytes += size
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }
}
