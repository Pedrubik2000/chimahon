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

    @Test
    fun `glossary senses in li tags are kept apart`() {
        val raw = """[{"type":"structured-content","content":{"tag":"ul","content":[""" +
            """{"tag":"li","content":"to wait"},{"tag":"li","content":"to await"}]}}]"""
        glossaryText(raw) shouldBe "to wait; to await"
    }

    @Test
    fun `glossary drops example sentences and keeps the part of speech`() {
        val raw = """[{"type":"structured-content","content":[{"tag":"span","content":"noun"},""" +
            """{"tag":"ol","content":[{"tag":"li","content":"heating power"},{"tag":"li","content":"thermal power"}]},""" +
            """{"tag":"div","data":{"content":"example-sentence"},"content":"火力とお鍋"}]}]"""
        glossaryText(raw) shouldBe "noun; heating power; thermal power"
    }

    @Test
    fun `glossary headword before a sense list leaves no leading separator, a semicolon in the text stays`() {
        val raw = """[{"type":"structured-content","content":[{"tag":"span","content":"まちじかん[3]【待ち時間】"},""" +
            """{"tag":"ol","content":[{"tag":"li","content":"待つ間の時間"}]}]}]"""
        glossaryText(raw) shouldBe "待つ間の時間"
        glossaryText("[\"semicolon (;)\"]") shouldBe "semicolon (;)"
    }
}
