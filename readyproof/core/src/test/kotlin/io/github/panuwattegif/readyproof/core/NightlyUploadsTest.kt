package io.github.panuwattegif.readyproof.core

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.*

class NightlyUploadsTest {
    private val day = LocalDate.of(2026, 10, 7)
    private fun file(key: String, done: Boolean = false, shop: String? = "kaprao") =
        NightlyUploads.FileState(key, shop, done)
    private fun marker(vararg keys: String) = NightlyUploads.FileState("marker", "kaprao", false, keys.toList())

    @Test fun daytimeAndFutureDatesNeverRelease() {
        assertFalse(NightlyUploads.canRelease(day, day.atTime(18, 59)))
        assertFalse(NightlyUploads.canRelease(day.plusDays(1), day.atTime(23, 59)))
    }
    @Test fun closingTimeAndPriorDayRecoveryRelease() {
        assertTrue(NightlyUploads.canRelease(day, day.atTime(19, 0)))
        assertTrue(NightlyUploads.canRelease(day, LocalDateTime.of(2026, 10, 8, 8, 0)))
    }
    @Test fun stagedPhotosAndLegacyEntriesStayLocalWithoutABatch() {
        assertTrue(NightlyUploads.releasedKeys(listOf(file("photo"), file("legacy", shop = null)), "kaprao").isEmpty())
    }
    @Test fun partialProofBatchReleasesActualFilesWithoutDemandingMissingReady() {
        val all = listOf(file("validReady"), file("summary"), marker("validReady", "summary"), file("newDayPhoto"))
        assertEquals(setOf("validReady", "summary", "marker"), NightlyUploads.releasedKeys(all, "kaprao"))
    }
    @Test fun wrongShopDependencyNeverReleases() {
        assertTrue(NightlyUploads.releasedKeys(listOf(file("daughter", shop = "daughter"), marker("daughter")), "kaprao").isEmpty())
    }
    @Test fun interruptedStagingDoesNotBecomeACompleteBatch() {
        assertTrue(NightlyUploads.releasedKeys(listOf(file("photo"), marker("photo", "missing")), "kaprao").isEmpty())
    }
    @Test fun markerWaitsForEveryVerifiedUploadAndRetry() {
        val m = marker("photo", "summary")
        assertFalse(NightlyUploads.markerReady(m, listOf(file("photo", true), file("summary"))))
        assertTrue(NightlyUploads.markerReady(m, listOf(file("photo", true), file("summary", true))))
        assertFalse(NightlyUploads.markerReady(m, listOf(file("photo", true))))
    }
    @Test fun markerCannotCertifyWrongShopOrAnotherMarker() {
        val m = marker("photo")
        assertFalse(NightlyUploads.markerReady(m, listOf(file("photo", true, "daughter"))))
        assertFalse(NightlyUploads.markerReady(m, listOf(NightlyUploads.FileState("photo", "kaprao", true, emptyList()))))
    }
}
