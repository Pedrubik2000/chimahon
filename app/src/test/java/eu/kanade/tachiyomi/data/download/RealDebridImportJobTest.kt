package eu.kanade.tachiyomi.data.download

import io.kotest.assertions.throwables.shouldThrow
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
    fun `a fallback runs without asking only when it adds nothing but missing volumes`() {
        // MASHLE, 2026-11-21: X3DL "1-5" held 3-5; Rawxt has 1-2, Nyaa a 1-18 torrent
        val missing = setOf(1, 2)
        RealDebridImportJob.autoFallback("https://rawxt.example/1-2.rar", setOf(1, 2), missing) shouldBe true
        RealDebridImportJob.autoFallback("https://rawxt.example/1.rar", setOf(1), missing) shouldBe true
        RealDebridImportJob.autoFallback("https://x3dl.example/1-5.rar", setOf(1, 2, 3, 4, 5), missing) shouldBe false
        RealDebridImportJob.autoFallback("magnet:?xt=urn:btih:abc", setOf(1, 2), missing) shouldBe false
        RealDebridImportJob.autoFallback("https://rawxt.example/x.rar", null, missing) shouldBe false
        RealDebridImportJob.autoFallback("https://rawxt.example/3.rar", setOf(3), missing) shouldBe false
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
        // MASHLE, 2026-11-21: X3DL held 3-5, Rawxt's link was gone
        RealDebridImportJob.missingReason(setOf(1, 2), listOf("X3DL"), listOf("Rawxt")) shouldBe
            "Vol. 1, 2 were not in the pack (X3DL); dead link: Rawxt"
        RealDebridImportJob.missingReason(setOf(1, 2), emptyList(), listOf("X3DL", "Rawxt")) shouldBe
            "Vol. 1, 2 were not found; dead link: X3DL, Rawxt"
    }

    @Test
    fun `the user answer gives premium and days left, or a refused key`() {
        // /user body, trimmed; "premium" = seconds of premium left (214 days)
        val premium = """{"id":1,"username":"x","type":"premium","premium":18489600,"expiration":"2027-06-24T00:00:00.000Z"}"""
        RealDebridImportJob.parseUser(200, premium) shouldBe RealDebridImportJob.Account(premium = true, daysLeft = 214)
        val free = """{"id":1,"username":"x","type":"free","premium":0}"""
        RealDebridImportJob.parseUser(200, free) shouldBe RealDebridImportJob.Account(premium = false, daysLeft = 0)
        RealDebridImportJob.parseUser(401, "") shouldBe RealDebridImportJob.Account(false, 0, tokenRejected = true)
        RealDebridImportJob.parseUser(403, """{"error":"permission_denied"}""") shouldBe
            RealDebridImportJob.Account(false, 0, tokenRejected = true)
        shouldThrow<IllegalStateException> { RealDebridImportJob.parseUser(503, "") }
    }
}
