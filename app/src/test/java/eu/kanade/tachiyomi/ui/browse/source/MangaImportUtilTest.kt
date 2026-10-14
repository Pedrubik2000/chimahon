package eu.kanade.tachiyomi.ui.browse.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaImportUtilTest {

    @Test
    fun `separate downloads of one series get one name`() {
        val names = listOf(
            "[Group] Yotsuba&! v01-03 (Digital).rar",
            "Yotsuba&! v03-05.rar",
            "Yotsuba&! Vol. 06-09 (2020).zip",
            "Yotsuba&! 2.cbz",
            "Yotsuba&! 2w.cbz",
            "Yotsuba&! 01-03",
            "Yotsuba&!_v10",
        )
        names.map { MangaImportUtil.seriesTitle(it) }.distinct() shouldBe listOf("Yotsuba&!")
    }

    @Test
    fun `keeps dots and numbers that belong to the title`() {
        MangaImportUtil.seriesTitle("Dr. Stone v01.cbz") shouldBe "Dr. Stone"
        MangaImportUtil.seriesTitle("Mob Psycho 100 v01-03.rar") shouldBe "Mob Psycho 100"
        MangaImportUtil.seriesTitle("よつばと! 第01巻.zip") shouldBe "よつばと!"
    }

    @Test
    fun `key ignores case and punctuation`() {
        MangaImportUtil.seriesKey("Dr. Stone") shouldBe MangaImportUtil.seriesKey("dr stone")
    }
}
