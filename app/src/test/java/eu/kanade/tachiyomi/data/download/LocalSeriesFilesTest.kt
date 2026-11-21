package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.ui.browse.source.MangaImportUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The pure rules RealDebridImportJob follows in a series folder; names are real folder listings from the tablet. */
class LocalSeriesFilesTest {

    private val tadade = (1..5).map { "第%02d巻.rar".format(it) } + (6..9).map { "第%02d巻 w.rar".format(it) } +
        listOf("第10巻 w", "第04巻")
    private val kuso = listOf("第01巻.rar", "第02巻.rar", "第03巻 r.rar") + (4..8).map { "第%02d巻.rar".format(it) } +
        listOf("第09巻", "Vol. 10", "Vol. 11")
    private val killMeBaby = (1..16).filter { it != 15 }.map { "第%02d巻.rar".format(it) } +
        listOf("第15巻 r.rar", "Vol. 15", "_ _キルミーベイベー_.rar")

    /** What the job does with one entry of a pack: its new name, null when the folder has that volume. */
    private fun entry(folder: String, names: List<String>, entry: String): String? {
        val name = MangaImportUtil.restyle(MangaImportUtil.volumeName(entry), MangaImportUtil.japaneseStyle(names))
        val present = MangaImportUtil.volumeNumbers(folder, names)
        return name.takeUnless { MangaImportUtil.volumeNumber(folder, it) in present }
    }

    @Test
    fun `a volume letter is the same volume, both naming styles count`() {
        MangaImportUtil.volumeNumbers("Tadade wa daka remasen", tadade) shouldBe (1..10).toSet()
        MangaImportUtil.volumeNumbers("Kuso Onna ni Sachi Are", kuso) shouldBe (1..11).toSet()
        MangaImportUtil.volumeNumbers("Kill Me Baby", killMeBaby) shouldBe (1..16).toSet()
        MangaImportUtil.volumeNumber("MASHLE", "第03巻 s.rar") shouldBe 3
        MangaImportUtil.volumeNumber("MASHLE", "第12巻 w") shouldBe 12
        MangaImportUtil.volumeNumber("Kill Me Baby", "_ _キルミーベイベー_.rar") shouldBe null
    }

    @Test
    fun `pack entries already in the folder are skipped, the others keep the folder's style`() {
        // the re-fetch that made the 第04巻 folder next to 第04巻.rar
        entry("Tadade wa daka remasen", tadade.dropLast(1), "[檜原フキ] タダでは抱かれません 第04巻.rar") shouldBe null
        entry("Tadade wa daka remasen", tadade, "[檜原フキ] タダでは抱かれません 第11巻.rar") shouldBe "第11巻"
        // "Vol. 15" next to 第15巻 r.rar: another style, same volume
        entry("Kill Me Baby", killMeBaby.filter { it != "Vol. 15" }, "Kill Me Baby v15 (Digital).cbz") shouldBe null
        entry("Kuso Onna ni Sachi Are", kuso, "Kuso Onna ni Sachi Are v12.cbz") shouldBe "第12巻"
        entry("Kuso Onna ni Sachi Are", kuso, "[Grp] Kuso Onna ni Sachi Are 第03巻 s.rar") shouldBe null
    }

    @Test
    fun `naming style is the folder's majority, 第NN巻 by default`() {
        MangaImportUtil.japaneseStyle(kuso) shouldBe true
        MangaImportUtil.japaneseStyle(emptyList()) shouldBe true
        MangaImportUtil.japaneseStyle(listOf("Vol. 01.cbz", "Vol. 02.cbz", "第03巻.rar")) shouldBe false
        MangaImportUtil.restyle("Vol. 10", japanese = true) shouldBe "第10巻"
        MangaImportUtil.restyle("第03巻 r", japanese = false) shouldBe "Vol. 03 r"
        MangaImportUtil.restyle("_ _キルミーベイベー_", japanese = true) shouldBe "_ _キルミーベイベー_"
    }

    @Test
    fun `folder order - linked series, then any of the title's names, then none`() {
        val folders = listOf("Kuso Onna ni Sachi Are", "Kill Me Baby", "MASHLE", "Tadade wa daka remasen")
        val names = listOf("キルミーベイベー", "Kill Me Baby")
        MangaImportUtil.chooseFolder(listOf("MASHLE"), folders, names) shouldBe "MASHLE"
        MangaImportUtil.chooseFolder(listOf("Gone"), folders, names) shouldBe "Kill Me Baby"
        MangaImportUtil.chooseFolder(emptyList(), folders, listOf("マッシュル-MASHLE-", "Mashle", "Mashle: Magic and Muscles")) shouldBe "MASHLE"
        // spaces and case don't matter
        MangaImportUtil.chooseFolder(emptyList(), folders, listOf("タダでは抱かれません", "Tadade wa Dakaremasen")) shouldBe
            "Tadade wa daka remasen"
        MangaImportUtil.chooseFolder(emptyList(), folders, listOf("よつばと!", "Yotsuba to!")) shouldBe null
        MangaImportUtil.chooseFolder(emptyList(), folders, listOf("Kuso Onna ni Sachi Are 第03巻")) shouldBe "Kuso Onna ni Sachi Are"
    }

    @Test
    fun `junk - small, no number, from a pack with numbered volumes`() {
        val mb = 1024L * 1024
        MangaImportUtil.isJunk(hasNumber = false, packHasNumbered = true, bytes = 3 * mb) shouldBe true
        MangaImportUtil.isJunk(hasNumber = false, packHasNumbered = false, bytes = 3 * mb) shouldBe false // one-shot
        MangaImportUtil.isJunk(hasNumber = false, packHasNumbered = true, bytes = 80 * mb) shouldBe false // an extra volume
        MangaImportUtil.isJunk(hasNumber = true, packHasNumbered = true, bytes = 3 * mb) shouldBe false
        MangaImportUtil.isJunk(hasNumber = false, packHasNumbered = true, bytes = -1) shouldBe false // size unknown
    }
}
