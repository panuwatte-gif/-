package io.github.panuwattegif.readyproof.core

/** A bitmap may cover normal History rows alongside a delayed row. Never label them all delayed. */
object CaptureFeedback {
    fun delayedGfs(record: Record): List<String> = record.items
        .filter { it.type == ObsType.DELAY }.map { it.gf }.distinct()

    fun status(record: Record): String = when (record.kind) {
        RecordKind.DELAY -> delayedGfs(record).takeIf { it.isNotEmpty() }
            ?.let { "ล่าช้า " + it.joinToString(", ") } ?: "เก็บภาพ History แล้ว"
        RecordKind.READY -> "เก็บภาพ Ready แล้ว"
        RecordKind.HISTORY -> "เก็บภาพ History แล้ว"
        else -> record.kind.label
    }

    fun notification(record: Record): String? = when {
        record.kind == RecordKind.DELAY && delayedGfs(record).isNotEmpty() -> "📸 " + status(record)
        record.kind == RecordKind.MANUAL -> "📸 เก็บภาพแล้ว"
        else -> null
    }
}
