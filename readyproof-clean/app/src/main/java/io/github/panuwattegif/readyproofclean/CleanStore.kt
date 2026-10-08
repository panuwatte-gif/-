package io.github.panuwattegif.readyproofclean

import android.content.Context
import android.net.Uri
import io.github.panuwattegif.readyproofclean.core.Json
import io.github.panuwattegif.readyproofclean.core.bool
import io.github.panuwattegif.readyproofclean.core.int
import io.github.panuwattegif.readyproofclean.core.long
import io.github.panuwattegif.readyproofclean.core.objList
import io.github.panuwattegif.readyproofclean.core.str
import io.github.panuwattegif.readyproofclean.core.strList
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class HistoryInstance(
    val gf: String,
    val doneAt: String?,
    val cancelled: Boolean,
    val delayed: Boolean,
    val delayMin: Int?,
    val date: String,
) {
    val key: String get() = listOf(date, gf, doneAt ?: "UNKNOWN", if (cancelled) "C" else "D").joinToString("|")
}

data class CleanEvent(
    val kind: String,
    val t: Long,
    val date: String,
    val shop: String,
    val gfs: List<String> = emptyList(),
    val uri: String? = null,
    val instances: List<HistoryInstance> = emptyList(),
    val completed: Int? = null,
    val cancelled: Int? = null,
    val total: Int? = null,
    val complete: Boolean? = null,
    val note: String? = null,
)

data class MatchedDelay(
    val instance: HistoryInstance,
    val readyUri: String?,
    val historyUri: String?,
)

data class DaySummary(
    val shop: String,
    val date: LocalDate,
    val readySeen: Int,
    val readyProof: Int,
    val readyPending: List<String>,
    val observedHistory: Int,
    val headerCompleted: Int?,
    val headerCancelled: Int?,
    val headerTotal: Int?,
    val delayed: Int,
    val withReadyEvidence: Int,
    val actualDelayed: Int,
    val grabPct: Double?,
    val actualPct: Double?,
    val complete: Boolean,
    val status: String?,
    val cases: List<MatchedDelay>,
) {
    val denominator: Int? get() = headerTotal?.takeIf { it > 0 } ?: observedHistory.takeIf { complete && it > 0 }
}

object CleanStore {
    const val SHOP_KAPRAO = "kaprao"
    const val SHOP_LUKSAO = "luksao"
    private const val PREF = "readyproof_clean"
    private const val PREF_SHOP = "shop"
    private val lock = Any()
    private val zone = ZoneId.of("Asia/Bangkok")

    fun selectedShop(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(PREF_SHOP, SHOP_KAPRAO) ?: SHOP_KAPRAO

    fun setShop(ctx: Context, shop: String) {
        require(shop == SHOP_KAPRAO || shop == SHOP_LUKSAO)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(PREF_SHOP, shop).apply()
    }

    fun shopLabel(shop: String) = if (shop == SHOP_LUKSAO) "ลูกสาวทำเอง" else "กะเพรา"

    private fun dir(ctx: Context, shop: String, day: String): File =
        File(ctx.filesDir, "clean/$shop/$day").apply { mkdirs() }

    private fun logFile(ctx: Context, shop: String, day: String) = File(dir(ctx, shop, day), "events.jsonl")

    fun append(ctx: Context, e: CleanEvent) {
        val line = Json.write(linkedMapOf(
            "kind" to e.kind, "t" to e.t, "date" to e.date, "shop" to e.shop,
            "gfs" to e.gfs.ifEmpty { null }, "uri" to e.uri,
            "instances" to e.instances.map { i -> linkedMapOf(
                "gf" to i.gf, "doneAt" to i.doneAt, "cancelled" to i.cancelled,
                "delayed" to i.delayed, "delayMin" to i.delayMin, "date" to i.date,
            ) }.ifEmpty { null },
            "completed" to e.completed, "cancelledCount" to e.cancelled, "total" to e.total,
            "complete" to e.complete, "note" to e.note,
        ))
        synchronized(lock) { logFile(ctx, e.shop, e.date).appendText(line + "\n", Charsets.UTF_8) }
    }

    fun load(ctx: Context, shop: String, day: LocalDate): List<CleanEvent> {
        val f = logFile(ctx, shop, day.toString())
        if (!f.exists()) return emptyList()
        return synchronized(lock) {
            f.readLines(Charsets.UTF_8).mapNotNull { line ->
                runCatching {
                    val m = Json.parseObject(line)
                    val date = m.str("date") ?: return@runCatching null
                    val eventShop = m.str("shop") ?: return@runCatching null
                    CleanEvent(
                        kind = m.str("kind") ?: return@runCatching null,
                        t = m.long("t") ?: 0L,
                        date = date,
                        shop = eventShop,
                        gfs = m.strList("gfs") ?: emptyList(),
                        uri = m.str("uri"),
                        instances = m.objList("instances").mapNotNull { x ->
                            val gf = x.str("gf") ?: return@mapNotNull null
                            HistoryInstance(
                                gf = gf,
                                doneAt = x.str("doneAt"),
                                cancelled = x.bool("cancelled") ?: false,
                                delayed = x.bool("delayed") ?: false,
                                delayMin = x.int("delayMin"),
                                date = x.str("date") ?: date,
                            )
                        },
                        completed = m.int("completed"),
                        cancelled = m.int("cancelledCount"),
                        total = m.int("total"),
                        complete = m.bool("complete"),
                        note = m.str("note"),
                    )
                }.getOrNull()
            }.filter { it.shop == shop && it.date == day.toString() }
        }
    }

    fun summary(ctx: Context, shop: String, day: LocalDate): DaySummary {
        val events = load(ctx, shop, day)
        val readySeen = events.filter { it.kind == "READY_SEEN" }.flatMap { it.gfs }.toSet()
        val readyProofEvents = events.filter { it.kind == "READY_PROOF" && it.uri != null }
        val readyProofGfs = readyProofEvents.flatMap { it.gfs }.toSet()

        val instances = LinkedHashMap<String, HistoryInstance>()
        events.filter { it.kind == "HISTORY_SEEN" || it.kind == "HISTORY_PROOF" }.forEach { e ->
            e.instances.forEach { i -> instances[i.key] = i }
        }
        val historyProof = LinkedHashMap<String, String>()
        events.filter { it.kind == "HISTORY_PROOF" && it.uri != null }.forEach { e ->
            e.instances.forEach { i -> historyProof[i.key] = e.uri!! }
        }
        val totals = events.lastOrNull { it.kind == "HISTORY_TOTAL" && it.total != null }
        val status = events.lastOrNull { it.kind == "SWEEP_STATUS" }
        val delayedInstances = instances.values.filter { it.delayed }

        fun endMillis(i: HistoryInstance): Long? {
            val t = i.doneAt?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
            return day.atTime(t).atZone(zone).toInstant().toEpochMilli()
        }
        val byGf = instances.values.groupBy { it.gf }
        val cases = delayedInstances.map { d ->
            val end = endMillis(d)
            val previous = byGf[d.gf].orEmpty().mapNotNull { p ->
                val pm = endMillis(p)
                if (pm != null && end != null && pm < end) pm else null
            }.maxOrNull()
            val candidate = if (end == null) null else readyProofEvents
                .filter { d.gf in it.gfs && it.t <= end && it.t > (previous ?: day.atStartOfDay(zone).toInstant().toEpochMilli()) }
                .minByOrNull { it.t }
            MatchedDelay(d, candidate?.uri, historyProof[d.key])
        }

        val total = totals?.total?.takeIf { it > 0 } ?: instances.size.takeIf { status?.complete == true && it > 0 }
        val withReady = cases.count { it.readyUri != null }
        val actual = (cases.size - withReady).coerceAtLeast(0)
        return DaySummary(
            shop = shop,
            date = day,
            readySeen = readySeen.size,
            readyProof = readyProofGfs.size,
            readyPending = (readySeen - readyProofGfs).sorted(),
            observedHistory = instances.size,
            headerCompleted = totals?.completed,
            headerCancelled = totals?.cancelled,
            headerTotal = totals?.total,
            delayed = cases.size,
            withReadyEvidence = withReady,
            actualDelayed = actual,
            grabPct = total?.let { cases.size * 100.0 / it },
            actualPct = total?.let { actual * 100.0 / it },
            complete = status?.complete == true,
            status = status?.note,
            cases = cases,
        )
    }

    fun allImageUris(ctx: Context, shop: String, day: LocalDate): List<Uri> =
        load(ctx, shop, day).filter { it.uri != null && it.kind in setOf("READY_PROOF", "HISTORY_PROOF") }
            .mapNotNull { runCatching { Uri.parse(it.uri) }.getOrNull() }.distinctBy { it.toString() }

    fun reportImageUris(ctx: Context, shop: String, day: LocalDate): List<Uri> {
        val s = summary(ctx, shop, day)
        val out = ArrayList<String>()
        s.cases.forEach { c ->
            c.readyUri?.let(out::add)
            c.historyUri?.let(out::add)
        }
        return out.distinct().map(Uri::parse)
    }

    fun summaryText(ctx: Context, shop: String, day: LocalDate): String {
        val s = summary(ctx, shop, day)
        fun pct(v: Double?) = v?.let { "%.2f%%".format(java.util.Locale.US, it) } ?: "UNKNOWN"
        return buildString {
            append("วันที่: ").append(day).append('\n')
            append("ร้าน: ").append(shopLabel(shop)).append('\n')
            append("Ready เห็น: ").append(s.readySeen).append('\n')
            append("Ready มีภาพ: ").append(s.readyProof).append('\n')
            append("Ready Pending: ").append(s.readyPending.size)
            if (s.readyPending.isNotEmpty()) append(" (").append(s.readyPending.joinToString()).append(')')
            append('\n')
            append("History header: ")
            if (s.headerTotal != null) append("ทั้งหมด ").append(s.headerTotal)
                .append(" / สำเร็จ ").append(s.headerCompleted ?: 0)
                .append(" / ยกเลิก ").append(s.headerCancelled ?: 0)
            else append("UNKNOWN")
            append('\n')
            append("History อ่านรายออเดอร์: ").append(s.observedHistory).append('\n')
            append("Grab Delayed: ").append(s.delayed).append(" / ").append(pct(s.grabPct)).append('\n')
            append("มีหลักฐาน Ready: ").append(s.withReadyEvidence).append('\n')
            append("Delayed จริงหลังหักหลักฐาน: ").append(s.actualDelayed).append(" / ").append(pct(s.actualPct)).append('\n')
            append("สถานะการกวาด: ").append(if (s.complete) "COMPLETE" else "INCOMPLETE").append('\n')
            s.status?.let { append("หมายเหตุ: ").append(it).append('\n') }
            if (s.cases.isNotEmpty()) {
                append("\nDelayed cases:\n")
                s.cases.forEach { c ->
                    append(c.instance.gf).append(" @ ").append(c.instance.doneAt ?: "UNKNOWN")
                        .append(" delayed=").append(c.instance.delayMin?.let { "$it min" } ?: "YES")
                        .append(" ready=").append(if (c.readyUri != null) "YES" else "NO")
                        .append(" historyPhoto=").append(if (c.historyUri != null) "YES" else "NO").append('\n')
                }
            }
        }
    }
}
