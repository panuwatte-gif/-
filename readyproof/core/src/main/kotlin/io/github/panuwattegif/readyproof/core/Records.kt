package io.github.panuwattegif.readyproof.core

/** Why a record was written. */
enum class RecordKind(val label: String) {
    /** The "พร้อมจัดส่ง" button was tapped. */
    PRESS("กดพร้อมจัดส่ง"),
    /** An order was seen in a ready state (waiting for / with driver). */
    READY("READY"),
    /** The history list showed a delayed order. */
    DELAY("ล่าช้า"),
    /** Captured by hand (accessibility button or test). */
    MANUAL("แคปเอง"),
    /** History rows seen without a screenshot (used for the daily totals). */
    SEEN("เห็นในประวัติ"),
    HISTORY("HISTORY"),
}

/** What was observed about one order. */
enum class ObsType { PRESS, READY, DELAY, DONE, CANCELLED, VISIBLE }

data class Item(
    val gf: String,
    val type: ObsType,
    /** The status line that triggered the observation, e.g. "กำลังค้นหาคนขับ...". */
    val status: String? = null,
    /** Minutes late as shown in history ("ล่าช้าไป 4 นาที" -> 4). */
    val delayMin: Int? = null,
    /** Finish time as HH:mm (24h) from "เสร็จสมบูรณ์เมื่อ 12:56 PM". */
    val doneAt: String? = null,
    /** Prep countdown shown when the button was tapped, e.g. "5:53". */
    val countdown: String? = null,
    /** Strings of the order card, for search and for the report. */
    val card: List<String> = emptyList(),
    val historyDate: String? = null,
)

data class Record(
    val id: String,
    /** Epoch millis when the screen was captured / seen. */
    val t: Long,
    val kind: RecordKind,
    val items: List<Item> = emptyList(),
    /** Every order number visible on screen at that moment. */
    val visible: List<String> = emptyList(),
    /** content:// URI of the screenshot, null for text-only records. */
    val uri: String? = null,
    val file: String? = null,
    /** Label of the tapped button (PRESS). */
    val click: String? = null,
    val note: String? = null,
    /** Null on legacy/unconfigured records: never automatically route them to a shop. */
    val shopId: String? = null,
    /** Date read explicitly from History; clock-only rows remain unverified. */
    val historyDate: String? = null,
) {
    val gfs: List<String> get() = items.map { it.gf }.distinct()

    fun mentions(gf: String): Boolean = items.any { it.gf == gf } || visible.contains(gf)

    /** Text used by the search box. */
    fun searchText(): String = buildString {
        append(kind.label).append(' ')
        items.forEach { append(it.gf).append(' ').append(it.status.orEmpty()).append(' ').append(it.card.joinToString(" ")).append(' ') }
        visible.forEach { append(it).append(' ') }
        append(click.orEmpty()).append(' ').append(note.orEmpty()).append(' ').append(file.orEmpty())
    }
}

/** One JSON object per line; unknown fields are ignored so old logs stay readable. */
object RecordCodec {
    private const val MAX_CARD_TEXTS = 20
    private const val MAX_TEXT = 160

    fun encode(r: Record): String = Json.write(
        linkedMapOf(
            "v" to 1,
            "id" to r.id,
            "t" to r.t,
            "kind" to r.kind.name,
            "items" to r.items.map { encodeItem(it) },
            "visible" to r.visible.ifEmpty { null },
            "uri" to r.uri,
            "file" to r.file,
            "click" to r.click,
            "note" to r.note,
            "shopId" to r.shopId,
            "historyDate" to r.historyDate,
        )
    )

    private fun encodeItem(i: Item): Map<String, Any?> = linkedMapOf(
        "gf" to i.gf,
        "type" to i.type.name,
        "status" to i.status?.take(MAX_TEXT),
        "delayMin" to i.delayMin,
        "doneAt" to i.doneAt,
        "countdown" to i.countdown,
        "card" to i.card.take(MAX_CARD_TEXTS).map { it.take(MAX_TEXT) }.ifEmpty { null },
        "historyDate" to i.historyDate,
    )

    /** Returns null for blank, corrupt or unknown-kind lines instead of throwing. */
    fun decode(line: String): Record? {
        if (line.isBlank()) return null
        return try {
            val m = Json.parseObject(line)
            val kind = m.str("kind")?.let { k -> RecordKind.entries.firstOrNull { it.name == k } } ?: return null
            Record(
                id = m.str("id") ?: return null,
                t = m.long("t") ?: return null,
                kind = kind,
                items = m.objList("items").mapNotNull { decodeItem(it) },
                visible = m.strList("visible") ?: emptyList(),
                uri = m.str("uri"),
                file = m.str("file"),
                click = m.str("click"),
                note = m.str("note"),
                shopId = m.str("shopId"),
                historyDate = m.str("historyDate"),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeItem(m: Map<String, Any?>): Item? {
        val type = m.str("type")?.let { t -> ObsType.entries.firstOrNull { it.name == t } } ?: return null
        return Item(
            gf = m.str("gf") ?: return null,
            type = type,
            status = m.str("status"),
            delayMin = m.int("delayMin"),
            doneAt = m.str("doneAt"),
            countdown = m.str("countdown"),
            card = m.strList("card") ?: emptyList(),
            historyDate = m.str("historyDate"),
        )
    }
}
