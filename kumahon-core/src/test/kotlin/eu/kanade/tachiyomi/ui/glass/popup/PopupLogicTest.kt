package eu.kanade.tachiyomi.ui.glass.popup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

class PopupLogicTest {

    private fun lh(reading: String, n: Int) = PopupLogic.pitch(reading, n)!!.high.joinToString(" ") { if (it) "H" else "L" }

    @Test
    fun `sumimasen with downstep 4 is low high high high low`() {
        lh("すみません", 4) shouldBe "L H H H L"
        PopupLogic.pitch("すみません", 4)!!.drop shouldBe 3 // the line falls after せ
        PopupLogic.pitch("すみません", 4)!!.name shouldBe "nakadaka"
    }

    @Test
    fun `heiban atamadaka odaka`() {
        lh("さくら", 0) shouldBe "L H H"
        PopupLogic.pitch("さくら", 0)!!.drop shouldBe -1
        PopupLogic.pitch("さくら", 0)!!.name shouldBe "heiban"
        lh("いのち", 1) shouldBe "H L L"
        PopupLogic.pitch("いのち", 1)!!.name shouldBe "atamadaka"
        lh("はな", 2) shouldBe "L H"
        PopupLogic.pitch("はな", 2)!!.name shouldBe "odaka"
        PopupLogic.pitch("はな", 2)!!.drop shouldBe 1
    }

    @Test
    fun `small kana join the mora before and long vowels count`() {
        PopupLogic.morae("きょう") shouldBe listOf("きょ", "う")
        PopupLogic.morae("ざっし") shouldBe listOf("ざ", "っ", "し")
        PopupLogic.morae("コーヒー") shouldBe listOf("コ", "ー", "ヒ", "ー")
        lh("きょう", 1) shouldBe "H L"
        PopupLogic.pitch("", 1) shouldBe null
    }

    @Test
    fun `positions are distinct in dictionary order`() {
        PopupLogic.positions(listOf(listOf(4), listOf(4, 0), listOf(3))) shouldBe listOf(4, 0, 3)
    }

    @Test
    fun `furigana goes over the kanji only`() {
        PopupLogic.furigana("済みません", "すみません") shouldBe listOf("済" to "す", "みません" to "")
        PopupLogic.furigana("食べる", "たべる") shouldBe listOf("食" to "た", "べる" to "")
        PopupLogic.furigana("取り入れる", "とりいれる") shouldBe listOf("取" to "と", "り" to "", "入" to "い", "れる" to "")
        PopupLogic.furigana("ありがとう", "ありがとう") shouldBe listOf("ありがとう" to "")
        PopupLogic.furigana("日本語", "にほんご") shouldBe listOf("日本語" to "にほんご")
        PopupLogic.furigana("お願い", "おねがい") shouldBe listOf("お" to "", "願" to "ねが", "い" to "")
    }

    @Test
    fun `the bar follows the card`() {
        PopupLogic.bar("u", false) shouldBe PopupLogic.Bar.MINE
        PopupLogic.bar("d", true) shouldBe PopupLogic.Bar.RATE_DUE
        PopupLogic.bar("b", false) shouldBe PopupLogic.Bar.RATE_DUE
        PopupLogic.bar("k", true) shouldBe PopupLogic.Bar.RATE_EARLY
        PopupLogic.bar("k", false) shouldBe PopupLogic.Bar.MINE // known by a list, no card to rate
        PopupLogic.stateName(null) shouldBe "u"
        PopupLogic.stateName("x") shouldBe "u"
    }

    @Test
    fun `status line`() {
        val day = 86_400_000L
        val created = 1_700_000_000_000L // 14 Nov 2023
        val now = created + 10 * day
        PopupLogic.statusLine("u", null, null, now, ZoneOffset.UTC) shouldBe "New"
        PopupLogic.statusLine("k", null, null, now, ZoneOffset.UTC) shouldBe "Known"
        PopupLogic.statusLine("d", created, now - 1, now, ZoneOffset.UTC) shouldBe "Mined 14 Nov · due today"
        PopupLogic.statusLine("b", created, null, now, ZoneOffset.UTC) shouldBe "Mined 14 Nov · not rated yet"
        PopupLogic.statusLine("k", created, now + 5 * day + 1000, now, ZoneOffset.UTC) shouldBe "Mined 14 Nov · next review in 5 d"
    }
}
