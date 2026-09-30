package io.github.panuwattegif.readyproof.core

import java.time.LocalDateTime

/**
 * Screenshot file names follow the shop's existing habit ("GF-613_READY.jpg",
 * "GF-450_DELAY-10m_GF-697_DELAY-6m.jpg") plus a timestamp so names never collide.
 */
object Naming {
    private const val MAX_GFS = 4
    private val UNSAFE = Regex("[^A-Za-z0-9_-]")

    fun fileName(kind: RecordKind, items: List<Item>, visible: List<String>, at: LocalDateTime): String {
        val head = if (kind == RecordKind.DELAY) {
            items.filter { it.type == ObsType.DELAY }.distinctBy { it.gf }.take(MAX_GFS)
                .joinToString("_") { it.gf + "_DELAY" + (it.delayMin?.let { m -> "-${m}m" } ?: "") }
                .ifEmpty { "NOGF_DELAY" }
        } else {
            val gfs = items.map { it.gf }.distinct().ifEmpty { visible.distinct() }
            when {
                gfs.isEmpty() -> "NOGF_${kind.name}"
                else -> gfs.take(MAX_GFS).joinToString("_") +
                    (if (gfs.size > MAX_GFS) "_plus${gfs.size - MAX_GFS}" else "") + "_" + kind.name
            }
        }
        return UNSAFE.replace("${head}_${stamp(at)}", "_") + ".jpg"
    }

    /** Name inside an evidence set: "GF-613_READY.jpg", "GF-613_DELAY.jpg". */
    fun setName(gfTag: String, kind: String): String = UNSAFE.replace("${gfTag}_$kind", "_") + ".jpg"

    /** 2026-09-28_11-01-30 */
    fun stamp(at: LocalDateTime): String =
        "${at.year}-${Parsers.pad2(at.monthValue)}-${Parsers.pad2(at.dayOfMonth)}_" +
            "${Parsers.pad2(at.hour)}-${Parsers.pad2(at.minute)}-${Parsers.pad2(at.second)}"
}
