package eu.kanade.tachiyomi.data.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RealDebridImportJobTest {

    @Test
    fun `a pack labelled 1-5 that held only 3-5 already here misses 1 and 2`() {
        // MASHLE_01s-05.rar on the tablet, 2026-11-21: vol. 3, 4, 5 skipped, nothing written
        RealDebridImportJob.missingVolumes(
            promised = setOf(1, 2, 3, 4, 5),
            written = emptySet(),
            skipped = setOf(3, 4, 5),
            present = setOf(3, 4, 5),
        ) shouldBe setOf(1, 2)
        // everything came or was here
        RealDebridImportJob.missingVolumes(setOf(1, 2, 3), setOf(1, 2), setOf(3), setOf(3)) shouldBe emptySet()
        // a volume already on the tablet is not missing even when the pack lacked it
        RealDebridImportJob.missingVolumes(setOf(1, 2), setOf(1), emptySet(), setOf(2)) shouldBe emptySet()
    }

    @Test
    fun `fallback links are tried only when they can hold a missing volume`() {
        val missing = setOf(1, 2)
        RealDebridImportJob.canHold(setOf(1), missing) shouldBe true
        RealDebridImportJob.canHold(setOf(1, 2, 3, 4, 5), missing) shouldBe true
        RealDebridImportJob.canHold(setOf(3, 4, 5), missing) shouldBe false
        RealDebridImportJob.canHold(null, missing) shouldBe true // a job queued before volumes were stored per link
    }

    @Test
    fun `fallback volumes read from the input`() {
        RealDebridImportJob.decodeVolumes("1,2,3") shouldBe setOf(1, 2, 3)
        RealDebridImportJob.decodeVolumes("7") shouldBe setOf(7)
        RealDebridImportJob.decodeVolumes("") shouldBe null
    }

    @Test
    fun `the reason names the volumes and the sources`() {
        RealDebridImportJob.missingReason(setOf(2, 1), listOf("X3DL")) shouldBe "Vol. 1, 2 were not in the pack (X3DL)"
        RealDebridImportJob.missingReason(setOf(6), listOf("X3DL", "Excnn")) shouldBe "Vol. 6 was not in the pack (X3DL, Excnn)"
        RealDebridImportJob.missingReason(setOf(6), emptyList()) shouldBe "Vol. 6 was not in the pack"
    }
}
