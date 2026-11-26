package eu.kanade.tachiyomi.ui.glass.setup

import eu.kanade.tachiyomi.ui.glass.setup.GlassSetupLogic.Facts
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlassSetupLogicTest {

    private val all = listOf(false, true).flatMap { a ->
        listOf(false, true).flatMap { r -> listOf(false, true).map { s -> Facts(a, r, s) } }
    }

    @Test
    fun `the setup shows in the new look until done, whenever a needed fact is missing`() {
        all.size shouldBe 8
        all.forEach { f ->
            val complete = f.aniList && f.storage // a Real-Debrid key can wait
            GlassSetupLogic.shows(glassRoot = true, done = false, facts = f) shouldBe !complete
            GlassSetupLogic.shows(glassRoot = true, done = true, facts = f) shouldBe false
            GlassSetupLogic.shows(glassRoot = false, done = false, facts = f) shouldBe false
        }
    }

    @Test
    fun `it starts at the first missing step, or the summary`() {
        all.forEach { f ->
            val expected = when {
                !f.aniList -> 0
                !f.storage -> 2
                else -> GlassSetupLogic.SUMMARY
            }
            GlassSetupLogic.firstStep(f) shouldBe expected
        }
        GlassSetupLogic.missing(Facts(aniList = true, realDebrid = false, storage = false)) shouldBe listOf(2)
        GlassSetupLogic.missing(Facts(aniList = true, realDebrid = false, storage = true)) shouldBe emptyList()
        (0 until GlassSetupLogic.STEPS).filter { GlassSetupLogic.needed(it) } shouldBe listOf(0, 2) // Word colouring (5) can be skipped
    }

    @Test
    fun `seven steps, the summary after them, one name each`() {
        GlassSetupLogic.STEPS shouldBe 7
        GlassSetupLogic.SUMMARY shouldBe 7
        GlassSetupLogic.NAMES.size shouldBe GlassSetupLogic.STEPS
        GlassSetupLogic.NAMES.last() shouldBe "Known words"
        GlassSetupLogic.tag(5, 2) shouldBe "CAN SKIP"
        GlassSetupLogic.tag(6, 2) shouldBe "CAN SKIP"
    }

    @Test
    fun `the static sample colours 入学 blue, 試験 red and 合格 green`() {
        val s = GlassSetupLogic.SAMPLE
        val c = GlassSetupLogic.staticSample()
        c.size shouldBe s.length
        fun at(w: String) = c.slice(s.indexOf(w) until s.indexOf(w) + w.length).toSet()
        at("入学") shouldBe setOf(0xFF1565C0.toInt())
        at("試験") shouldBe setOf(0xFFD32F2F.toInt())
        at("合格") shouldBe setOf(0xFF2E7D32.toInt())
        c.count { it != 0 } shouldBe 6
    }

    @Test
    fun `the missing line names one, two or three steps`() {
        GlassSetupLogic.missingLine(listOf("Storage")) shouldBe "Finish Storage first"
        GlassSetupLogic.missingLine(listOf("Real-Debrid", "Storage")) shouldBe "Finish Real-Debrid and Storage first"
        GlassSetupLogic.missingLine(listOf("AniList", "Real-Debrid", "Storage")) shouldBe
            "Finish AniList, Real-Debrid and Storage first"
    }

    @Test
    fun `step tags`() {
        GlassSetupLogic.tag(0, 3) shouldBe "NEEDED"
        GlassSetupLogic.tag(1, 2) shouldBe "CAN SKIP"
        GlassSetupLogic.tag(3, 0) shouldBe "CAN SKIP"
        GlassSetupLogic.tag(GlassSetupLogic.SUMMARY, 0) shouldBe "READY"
        GlassSetupLogic.tag(GlassSetupLogic.SUMMARY, 1) shouldBe "1 NEEDED STEP LEFT"
        GlassSetupLogic.tag(GlassSetupLogic.SUMMARY, 2) shouldBe "2 NEEDED STEPS LEFT"
    }

    @Test
    fun `a key is shown as dots but its last 4 characters`() {
        GlassSetupLogic.masked("ABCDEFGH7QK2") shouldBe "••••••••7QK2"
        GlassSetupLogic.masked("7QK2") shouldBe "••••"
        GlassSetupLogic.masked("") shouldBe ""
    }

    @Test
    fun `the storage folder reads as on the canvas`() {
        GlassSetupLogic.shortPath("/storage/emulated/0/Chimahon dev") shouldBe "/sdcard/Chimahon dev"
        GlassSetupLogic.shortPath("/storage/emulated/0") shouldBe "/sdcard"
        GlassSetupLogic.shortPath("/storage/emulated/01/x") shouldBe "/storage/emulated/01/x"
        GlassSetupLogic.shortPath("/storage/1A2B-3C4D/Manga") shouldBe "/storage/1A2B-3C4D/Manga"
    }
}
