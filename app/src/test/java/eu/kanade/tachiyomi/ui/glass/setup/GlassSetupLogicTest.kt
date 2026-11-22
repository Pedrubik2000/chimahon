package eu.kanade.tachiyomi.ui.glass.setup

import eu.kanade.tachiyomi.ui.glass.setup.GlassSetupLogic.Facts
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlassSetupLogicTest {

    private val all = listOf(false, true).flatMap { a ->
        listOf(false, true).flatMap { r -> listOf(false, true).map { s -> Facts(a, r, s) } }
    }

    @Test
    fun `the setup shows in the new look until done, whenever one fact is missing`() {
        all.size shouldBe 8
        all.forEach { f ->
            val complete = f.aniList && f.realDebrid && f.storage
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
                !f.realDebrid -> 1
                !f.storage -> 2
                else -> GlassSetupLogic.SUMMARY
            }
            GlassSetupLogic.firstStep(f) shouldBe expected
        }
        GlassSetupLogic.missing(Facts(aniList = true, realDebrid = false, storage = false)) shouldBe listOf(1, 2)
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
