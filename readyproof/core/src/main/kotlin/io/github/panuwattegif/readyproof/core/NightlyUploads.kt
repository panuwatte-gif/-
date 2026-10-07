package io.github.panuwattegif.readyproof.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Durable batch membership, rather than time alone, authorizes a staged file for upload. */
object NightlyUploads {
    data class FileState(val key: String, val shopId: String?, val uploaded: Boolean,
        val members: List<String>? = null)

    fun canRelease(day: LocalDate, now: LocalDateTime): Boolean =
        day.isBefore(now.toLocalDate()) || (day == now.toLocalDate() && !now.toLocalTime().isBefore(LocalTime.of(19, 0)))

    fun releasedKeys(files: List<FileState>, shopId: String): Set<String> {
        val own = files.filter { it.shopId == shopId }.associateBy { it.key }
        return own.values.filter { f -> f.members != null && f.members.all { own[it]?.members == null && it in own } }
            .flatMap { it.members!! + it.key }.toSet()
    }

    fun markerReady(marker: FileState, files: List<FileState>): Boolean {
        val own = files.filter { it.shopId == marker.shopId }.associateBy { it.key }
        return marker.members?.all { own[it]?.uploaded == true && own[it]?.members == null } == true
    }
}
