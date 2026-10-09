package io.github.panuwattegif.readyproofclean.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadyStaysTest {
    @Test fun sameWaitingOrderNeverNeedsRepeatedPhotoAndNewOneDoes() {
        val stays = ReadyStays()
        stays.seen(listOf("GF-001"))
        assertTrue(stays.needsProof("GF-001"))
        stays.proved(listOf("GF-001"))
        repeat(1000) { stays.seen(listOf("GF-001")); assertFalse(stays.needsProof("GF-001")) }
        stays.seen(listOf("GF-001", "GF-002"))
        assertTrue(stays.needsProof("GF-002"))
    }
    @Test fun partialViewDoesNotExpireButConfirmedAbsenceAllowsReusedNumber() {
        val stays = ReadyStays()
        stays.proved(listOf("GF-001", "GF-002"))
        stays.seen(listOf("GF-002"))
        assertFalse(stays.needsProof("GF-001"))
        stays.complete(listOf("GF-002"), 1000L)
        stays.complete(listOf("GF-002"), 15999L)
        assertFalse(stays.needsProof("GF-001"))
        stays.complete(listOf("GF-002"), 16000L)
        stays.seen(listOf("GF-001"))
        assertTrue(stays.needsProof("GF-001"))
        assertFalse(stays.needsProof("GF-002"))
    }
    @Test fun SeeingOrderAgainCancelsAbsence() {
        val stays = ReadyStays()
        stays.proved(listOf("GF-001"))
        stays.complete(emptyList(), 1000L)
        stays.seen(listOf("GF-001"))
        stays.complete(emptyList(), 20000L)
        assertFalse(stays.needsProof("GF-001"))
    }
}
