package io.github.panuwattegif.readyproof.core

import kotlin.test.Test
import kotlin.test.assertEquals

class UploadNamingTest {
    @Test fun legacyPhotoPrefixesAreRemovedWithoutChangingOrderOrTimestamp() {
        val original = "GF-269_READY_2026-10-07_19-30-00_4.jpg"
        for (name in listOf(original, "kaprao_$original", "kaprao_kaprao_$original")) {
            assertEquals(original, Naming.uploadName("kaprao", name, "abcdef0123456789", "image/jpeg"))
        }
    }

    @Test fun summariesAndCompletionMarkersKeepShopAndRevision() {
        assertEquals("kaprao_UPLOAD_DONE-2026-10-07_abcdef012345.json",
            Naming.uploadName("kaprao", "UPLOAD_DONE-2026-10-07.json", "abcdef0123456789", "application/json"))
    }
}
