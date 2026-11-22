package eu.kanade.presentation.reader.glass

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlassPageWordsTest {

    @Test
    fun `OCR fragments are not page words`() {
        // seen on the tablet as "new words": short kana pieces of a split word
        listOf("サイ", "そー", "たり", "の", "ー", "…", "！？", "").forEach { isPageWordFragment(it) shouldBe true }
        // real words, a single kanji too (whether it is new is kumapie's call)
        listOf("人", "ファミリー", "物件", "待ち時間", "学校").forEach { isPageWordFragment(it) shouldBe false }
    }
}
