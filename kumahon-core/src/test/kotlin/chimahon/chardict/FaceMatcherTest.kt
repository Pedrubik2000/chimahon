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
        val hits = FaceMatcher(dict).match("壬氏様が来た", tok("壬", "氏", "様", "が", "来た"), null)
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].start)
        assertEquals(3, hits[0].length)
        assertEquals("壬氏", hits[0].name)
        assertArrayEquals(png, hits[0].portrait)
    }

    @Test fun `a single headword and a join both match`() {
        val text = "壬氏が来た"
        assertEquals(1, FaceMatcher(dict).match(text, tok("壬氏", "が", "来た"), null).size)
        assertEquals(1, FaceMatcher(dict).match(text, tok("壬", "氏", "が", "来た"), null).size) // 2-piece join
    }

    private val names = Fake(
        dict.entries + mapOf(
            "千尋" to listOf(Glossary("c", card("w"))),
            "千尋さん" to listOf(Glossary("c", card("w", "さん"))),
            "山口" to listOf(Glossary("c", card("薬屋のひとりごと"))),
            "氏" to listOf(Glossary("c", card("w"))),
            "あい" to listOf(Glossary("c", card("w"))),
        ),
    )

    @Test fun `a name with its honorific gets one face at the name start`() {
        val hits = FaceMatcher(names).match("千尋さん ビール", tok("千尋", "さん", " ", "ビール"), null)
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].start)
        assertEquals("千尋", hits[0].name)
    }

    @Test fun `a surname alone and a name inside a text match`() {
        assertEquals(1, FaceMatcher(names).match("山口", tok("山口"), null).size)
        val hits = FaceMatcher(names).match("千尋さんビール", tok("千尋", "さん", "ビール"), null)
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].start)
    }

    @Test fun `with no work a single name must also be auto known, with a work the headword is enough`() {
        val one = tok("山口")
        assertTrue(FaceMatcher(names, { false }).match("山口", one, null).isEmpty())
        assertEquals(1, FaceMatcher(names, { true }).match("山口", one, null).size)
        assertEquals(1, FaceMatcher(names, { false }).match("山口", one, "薬屋のひとりごと").size)
    }

    /** What the device has (tablet/emulator, 2026-11-24): the real splitter's tokens of the page text and a stored glossary, whose JSON escapes the slash of the portrait path. */
    @Test fun `real tokens and a real escaped glossary give a face`() {
        val real = """[{"type":"structured-content","content":[{"tag":"div","style":{"fontSize":"0.85em"},"content":[{"tag":"span","content":"さん","style":{"fontWeight":"bold"}},{"tag":"span","content":" — Generic polite suffix (Mr.\/Ms.\/Mrs.)"}]},{"tag":"div","data":{"id":"name"},"content":"山口千尋"},{"tag":"div","data":{"id":"name-romaji"},"content":"Chihiro Yamaguchi"},{"tag":"div","data":{"id":"portrait-container"},"content":{"tag":"img","path":"img\/c205752.jpg","width":67,"height":100}},{"tag":"div","data":{"id":"source"},"content":"From: 焼いてるふたり (Yaiteru Futari)"}]}]"""
        val d = object : CharacterDict {
            override fun glossaries(word: String) = if (word == "千尋" || word == "千尋さん") listOf(Glossary("c", real)) else emptyList()
            override fun media(dict: String, path: String) = if (path == "img/c205752.jpg") png else null
        }
        val text = "千尋さんビール飲みたいって言ってましたよね"
        val tokens = listOf("千尋/千尋/0", "さん/さん/2", "ビール/ビール/4", "飲みたい/飲む/7", "って/って/11", "言ってました/言う/13", "よね/よね/19")
            .map { it.split("/").let { (s, b, at) -> Token(s, b, "", if (s == "さん") "接尾辞" else "名詞", at.toInt()) } }
        val hits = FaceMatcher(d, { false }).match(text, tokens, "焼いてるふたり")
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].start)
        assertEquals("千尋", hits[0].name)
    }

    @Test fun `a one-character or hiragana headword gets no face`() {
        assertTrue(FaceMatcher(names).match("氏", tok("氏"), null).isEmpty())
        assertTrue(FaceMatcher(names).match("あい", tok("あい"), null).isEmpty())
    }

    @Test fun `only the work's characters when a work is given`() {
        val m = FaceMatcher(dict)
        assertEquals(1, m.match("壬氏", tok("壬氏"), "薬屋のひとりごと 3").size)
        assertTrue(m.match("壬氏", tok("壬氏"), "別の作品").isEmpty())
    }

    @Test fun `a gap between pieces breaks the join and a hiragana word is skipped`() {
        val gapped = listOf(Token("壬", "壬", "", "名詞", 0), Token("氏", "氏", "", "名詞", 2))
        assertTrue(FaceMatcher(dict).match("壬 氏", gapped, null).isEmpty())
        assertTrue(FaceMatcher(dict).match("あ", listOf(Token("あ", "壬氏", "", "名詞", 0)), null).isEmpty())
    }

    @Test fun `entry parsing`() {
        assertEquals("Jinshi" to null, CharacterEntry.character(card("x")))
        assertEquals("Jinshi" to "様", CharacterEntry.character(card("x", "様")))
        assertNull(CharacterEntry.character("[{\"type\":\"structured-content\",\"content\":[\"JMdict gloss\"]}]"))
        assertEquals("c1.jpg", CharacterEntry.portraitPath(card("x")))
        assertTrue(CharacterEntry.fromWork(card("くま クマ 熊 ベアー (Kuma)"), "くまクマ熊ベアー17"))
        assertFalse(CharacterEntry.fromWork(card("薬屋のひとりごと"), "x"))
    }

    @Test fun `a title with a slash or an escape is read whole`() {
        assertTrue(CharacterEntry.fromWork(card("Fate/Grand Order"), "Fate/Grand Order 2"))
        val raw = """[{"type":"structured-content","content":[{"tag":"div","data":{"id":"source"},"content":"From: Pok${'\\'}u00e9mon Fate\/Zero"}]}]"""
        assertTrue(CharacterEntry.fromWork(raw, "Pokémon Fate/Zero"))
        assertFalse(CharacterEntry.fromWork(raw, "Pokémon Go"))
    }

    @Test fun `the portrait is the one in the portrait container, not an earlier voice actor image`() {
        val raw = """[{"type":"structured-content","content":[{"tag":"div","data":{"id":"name-romaji"},"content":"X"},{"tag":"img","path":"img\/c1_va.jpg"},{"tag":"div","data":{"id":"portrait-container"},"content":{"tag":"img","path":"img\/c1.jpg"}}]}]"""
        assertEquals("img/c1.jpg", CharacterEntry.portraitPath(raw))
        assertNull(CharacterEntry.portraitPath("""[{"content":[{"tag":"img","path":"a.jpg"}]}]"""))
    }

    @Test fun `portraits are asked through the cache`() {
        val asked = ArrayList<String>()
        val m = FaceMatcher(dict) { k, f -> asked += k; f() }
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
