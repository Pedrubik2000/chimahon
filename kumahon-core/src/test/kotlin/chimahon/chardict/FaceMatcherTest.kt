package chimahon.chardict

import chimahon.lang.JapaneseModel.Token
import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FaceMatcherTest {
    private val jinshi = Character(id = "1", name = "Jinshi", nameOriginal = "壬氏", role = "primary")
    private val builder = ContentBuilder(DictSettings())
    private val png = byteArrayOf(1, 2, 3)

    /** The glossary the real builder writes: the card alone, or under an honorific banner (as YomitanCharacterZip does). */
    private fun card(work: String, banner: String? = null): String {
        val items = builder.buildContent(jinshi, "c1.jpg", 80 to 100, null, null, work).join(",")
        val b = banner?.let { ContentBuilder.honorificBanner(it, "Very formal").toString() + "," }.orEmpty()
        return "[{\"type\":\"structured-content\",\"content\":[$b$items]}]"
    }

    private class Fake(val entries: Map<String, List<Glossary>>) : CharacterDict {
        var lookups = 0
        override fun glossaries(word: String) = entries[word].orEmpty().also { lookups++ }
        override fun media(dict: String, path: String) = if (path == "c1.jpg") byteArrayOf(1, 2, 3) else null
    }

    private val dict = Fake(
        mapOf(
            "壬氏" to listOf(Glossary("c", card("薬屋のひとりごと"))),
            "壬氏様" to listOf(Glossary("c", card("薬屋のひとりごと", "様"))),
        ),
    )

    private fun tok(vararg s: String): List<Token> {
        var at = 0
        return s.map { Token(it, it, "", "名詞", at).also { _ -> at += it.length } }
    }

    @Test fun `three pieces join to a name and the honorific is stripped`() {
        val hits = FaceMatcher(dict, { false }).match("壬氏様が来た", tok("壬", "氏", "様", "が", "来た"), null)
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].start)
        assertEquals(3, hits[0].length)
        assertEquals("壬氏", hits[0].name)
        assertArrayEquals(png, hits[0].portrait)
    }

    @Test fun `a single word must also be auto known, a join need not`() {
        val text = "壬氏が来た"
        assertTrue(FaceMatcher(dict, { false }).match(text, tok("壬氏", "が", "来た"), null).isEmpty())
        assertEquals(1, FaceMatcher(dict, { it == "壬氏" }).match(text, tok("壬氏", "が", "来た"), null).size)
        assertEquals(1, FaceMatcher(dict, { false }).match(text, tok("壬", "氏", "が", "来た"), null).size) // 2-piece join
    }

    @Test fun `only the work's characters when a work is given`() {
        val m = FaceMatcher(dict, { true })
        assertEquals(1, m.match("壬氏", tok("壬氏"), "薬屋のひとりごと 3").size)
        assertTrue(m.match("壬氏", tok("壬氏"), "別の作品").isEmpty())
    }

    @Test fun `a gap between pieces breaks the join and a hiragana word is skipped`() {
        val gapped = listOf(Token("壬", "壬", "", "名詞", 0), Token("氏", "氏", "", "名詞", 2))
        assertTrue(FaceMatcher(dict, { false }).match("壬 氏", gapped, null).isEmpty())
        assertTrue(FaceMatcher(dict, { true }).match("あ", listOf(Token("あ", "壬氏", "", "名詞", 0)), null).isEmpty())
    }

    @Test fun `entry parsing`() {
        assertEquals("Jinshi" to null, CharacterEntry.character(card("x")))
        assertEquals("Jinshi" to "様", CharacterEntry.character(card("x", "様")))
        assertNull(CharacterEntry.character("[{\"type\":\"structured-content\",\"content\":[\"JMdict gloss\"]}]"))
        assertEquals("c1.jpg", CharacterEntry.portraitPath(card("x")))
        assertTrue(CharacterEntry.fromWork(card("くま クマ 熊 ベアー (Kuma)"), "くまクマ熊ベアー17"))
        assertFalse(CharacterEntry.fromWork(card("薬屋のひとりごと"), "x"))
    }

    @Test fun `portraits are asked through the cache`() {
        val asked = ArrayList<String>()
        val m = FaceMatcher(dict, { true }) { k, f -> asked += k; f() }
        m.portrait("壬氏", "w")
        assertEquals(listOf("壬氏\nw"), asked)
    }

    @Test fun `the set of works rebuilds only when it changes`() {
        var w = ReadWorks.add(emptyList(), "薬屋のひとりごと")
        assertFalse(ReadWorks.needsRebuild(emptyList(), emptyList()))
        assertTrue(ReadWorks.needsRebuild(w, emptyList()))
        assertFalse(ReadWorks.needsRebuild(w, listOf("薬屋 のひとりごと")))
        assertEquals(w, ReadWorks.add(w, "薬屋のひとりごと ")) // same work again: nothing added
        assertEquals(2, ReadWorks.add(w, "Kuma").size)
        assertTrue(ReadWorks.needsRebuild(ReadWorks.add(w, "Kuma"), listOf("薬屋のひとりごと")))

        // a work AniList does not know is left out for a week, then tried again
        val nf = mapOf(CharacterEntry.workKey("Kuma") to 1_000L)
        val kuma = ReadWorks.add(w, "Kuma")
        assertFalse(ReadWorks.needsRebuild(kuma, listOf("薬屋のひとりごと"), nf, now = 2_000L))
        assertTrue(ReadWorks.needsRebuild(kuma, listOf("薬屋のひとりごと"), nf, now = 1_000L + ReadWorks.NOT_FOUND_RETRY_MS))
        assertFalse(ReadWorks.needsRebuild(ReadWorks.add(emptyList(), "Kuma"), emptyList(), nf, now = 2_000L))
        assertTrue(JSONArray(card("x")).length() == 1)
    }
}
