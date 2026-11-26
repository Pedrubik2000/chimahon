package eu.kanade.tachiyomi.ui.glass.review

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReviewLogicTest {

    @Test
    fun `furigana form gives spelling and reading`() {
        ReviewLogic.parseWord("魔法[まほう]") shouldBe ReviewLogic.Word("魔法", "まほう")
        ReviewLogic.parseWord(" 日本[にほん] 語[ご]") shouldBe ReviewLogic.Word("日本語", "にほんご")
        ReviewLogic.parseWord(" 食[た]べる") shouldBe ReviewLogic.Word("食べる", "たべる")
    }

    @Test
    fun `pitch marker is ignored and a kana word has no reading`() {
        ReviewLogic.parseWord("魔法[まほう]:0-") shouldBe ReviewLogic.Word("魔法", "まほう")
        ReviewLogic.parseWord("ありがとう") shouldBe ReviewLogic.Word("ありがとう", "")
    }

    @Test
    fun `kumapie word fields are cleaned like its own parser`() {
        ReviewLogic.parseWord("気[き]:0 に / 入[い]る:0") shouldBe ReviewLogic.Word("気に入る", "きにいる")
        ReviewLogic.parseWord("人[*ひと]:2-") shouldBe ReviewLogic.Word("人", "ひと")
        ReviewLogic.parseWord("拾[ひろ]\\う:0") shouldBe ReviewLogic.Word("拾う", "ひろう")
        ReviewLogic.parseWord("<b>魔法</b>[まほう]") shouldBe ReviewLogic.Word("魔法", "まほう")
    }

    @Test
    fun `card sentence loses html and furigana`() {
        ReviewLogic.cardSentence(" 日本[にほん] 語[ご]を") shouldBe "日本語を"
        ReviewLogic.cardSentence("私は<b> 食[た]べる</b>&nbsp;") shouldBe "私は食べる"
    }

    @Test
    fun `the word is marked wherever it occurs literally`() {
        ReviewLogic.segments("「魔法が使えない」", "魔法") shouldBe
            listOf("「" to false, "魔法" to true, "が使えない」" to false)
        ReviewLogic.segments("犬と猫", "魔法") shouldBe listOf("犬と猫" to false)
        ReviewLogic.segments("", "魔法") shouldBe emptyList()
    }

    @Test
    fun `meaning html becomes plain text with line breaks`() {
        ReviewLogic.plainText("<ol><li>magic</li><li>a &amp; b<br>c</li></ol>") shouldBe "magic\na & b\nc"
    }

    @Test
    fun `Again goes to the end of the sitting, other answers finish the card`() {
        val s = ReviewLogic.Session.start(listOf(1, 2, 3), newIds = setOf(1L))
        s.current shouldBe 1L
        s.newCount shouldBe 1
        val again = s.answer(1)
        again.queue shouldBe listOf(2L, 3L, 1L)
        again.done shouldBe 0
        again.againCount shouldBe 1
        again.newCount shouldBe 0
        again.dueCount shouldBe 2
        val good = again.answer(3)
        good.queue shouldBe listOf(3L, 1L)
        good.done shouldBe 1
        good.total shouldBe 3
    }

    @Test
    fun `undo brings the card back as current, one level`() {
        val s = ReviewLogic.Session.start(listOf(1, 2), newIds = emptySet())
        val again = s.answer(1)
        again.undo() shouldBe s
        val good = s.answer(3)
        good.undo().current shouldBe 1L
        good.undo().undo() shouldBe s
        s.undo() shouldBe s
        good.answer(3).answer(3).current shouldBe null
    }

    @Test
    fun `labels`() {
        ReviewLogic.intervalLabel(1) shouldBe "1 d"
        ReviewLogic.intervalLabel(45) shouldBe "2 mo"
        ReviewLogic.intervalLabel(400) shouldBe "1 y"
        ReviewLogic.whereLine("MASHLE", null, 42) shouldBe "MASHLE · p. 42"
    }

    @Test
    fun `sound mode cycles auto tap off`() {
        ReviewLogic.nextSound("auto") shouldBe "tap"
        ReviewLogic.nextSound("tap") shouldBe "off"
        ReviewLogic.nextSound("off") shouldBe "auto"
        ReviewLogic.normalSound("junk") shouldBe "auto"
        ReviewLogic.normalSound("off") shouldBe "off"
    }

    @Test
    fun `sentence pick and cache name`() {
        ReviewLogic.pickSentence("彼は", "x") shouldBe "彼は"
        ReviewLogic.pickSentence("  ", "あ") shouldBe "あ"
        ReviewLogic.pickSentence(null, "あ") shouldBe "あ"
        ReviewLogic.ttsCacheName("abc") shouldBe "a9993e364706816aba3e25717850c26c9cd0d89d.wav"
        ReviewLogic.ttsCacheName(" abc ") shouldBe ReviewLogic.ttsCacheName("abc")
    }

    @Test
    fun `reload keeps the face only for the same card`() {
        ReviewLogic.keepFace(5, 5) shouldBe true
        ReviewLogic.keepFace(5, 6) shouldBe false
        ReviewLogic.keepFace(null, 6) shouldBe false
        ReviewLogic.keepFace(null, null) shouldBe false
    }

    @Test
    fun `audio reading prefers the Reading field`() {
        ReviewLogic.audioReading("""{"Reading":"なすりつける"}""", ReviewLogic.Word("擦り付ける", "x")) shouldBe "なすりつける"
        ReviewLogic.audioReading("not json", ReviewLogic.Word("魔法", "まほう")) shouldBe "まほう"
    }
}
