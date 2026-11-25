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

    /** MASHLE's ソフィナ・ブリビア through the real zip builder: its headwords and cards are the dictionary the matcher gets. */
    private val mashle: CharacterDict by lazy {
        zipDict(listOf("9" to "ソフィナ・ブリビア", "8" to "マッシュ・バーンデッド", "7" to "ジャン＝ジャック", "6" to "ルーク･ガーデン", "5" to "ミナ·ローズ").map { (id, n) -> Triple(id, n, "MASHLE") })
    }

    /** The dictionary the real zip builder makes of these characters (id, native name, work), read back as the matcher reads it. */
    private fun zipDict(chars: List<Triple<String, String, String>>): CharacterDict {
        val z = YomitanCharacterZip(DictSettings(), revision = "r")
        for ((id, native, work) in chars)
            z.addCharacter(Character(id = id, name = "N$id", nameOriginal = native, role = "primary", firstNameHint = "A", lastNameHint = "B", imageBytes = byteArrayOf(1, 2, 3), imageExt = "jpg", imageWidth = 80, imageHeight = 100), work)
        val buf = java.io.ByteArrayOutputStream().also { z.write(it) }
        val cards = HashMap<String, MutableList<Glossary>>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(buf.toByteArray())).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (e.name.startsWith("term_bank_")) JSONArray(String(zin.readBytes(), Charsets.UTF_8)).let { a ->
                    for (i in 0 until a.length()) a.getJSONArray(i).let { t -> cards.getOrPut(t.getString(0)) { ArrayList() } += Glossary("c", t.getJSONArray(5).toString()) }
                }
                e = zin.nextEntry
            }
        }
        return object : CharacterDict {
            override fun glossaries(word: String) = cards[word].orEmpty()
            override fun media(dict: String, path: String) = png
        }
    }

    @Test fun `a piece shared by two characters of the work gets no face, the whole names still do`() {
        val d = zipDict(listOf(Triple("1", "レイン・エイムズ", "MASHLE"), Triple("2", "フィン・エイムズ", "MASHLE"), Triple("3", "ドット・バレット", "MASHLE")))
        val m = FaceMatcher(d, { false })
        assertTrue(m.match("エイムズが来た", real("エイムズ/0 が/4 来た/5"), "MASHLE").isEmpty())
        assertEquals("レイン・エイムズ", m.match("レイン・エイムズ", real("レイン/0 ・/3 エイムズ/4"), "MASHLE").single().name)
        assertEquals("ドット", m.match("ドットが来た", real("ドット/0 が/3 来た/4"), "MASHLE").single().name) // one owner
    }

    @Test fun `a namesake in another work does not decide for this work`() {
        // ローズ is a whole name in another work (so no piece there) and a piece of ミナ·ローズ here; Aime's エイムズ is shared only across works
        val d = zipDict(listOf(Triple("1", "ミナ·ローズ", "MASHLE"), Triple("2", "ローズ", "他の作品"), Triple("3", "レイン・エイムズ", "MASHLE"), Triple("4", "フィン・エイムズ", "他の作品")))
        val m = FaceMatcher(d, { false })
        var asked = 0
        val guarded = FaceMatcher(d, { false }, commonWord = { asked++; true })
        assertTrue(guarded.match("ローズが来た", real("ローズ/0 が/3 来た/4"), "MASHLE").isEmpty()) // still a piece here: the guard runs
        assertEquals(1, asked)
        assertEquals(1, m.match("エイムズが来た", real("エイムズ/0 が/4 来た/5"), "MASHLE").size) // one brother in this work
    }

    /** Sudachi's pieces with their real offsets; the middle dot is a symbol (not a word). */
    private fun real(s: String): List<Token> = s.split(" ").map { it.split("/") }.map { (surf, at) -> Token(surf, surf, "", if (surf.length == 1 && surf in NameParser.SEPARATORS) "補助記号" else "名詞", at.toInt()) }

    private val page = "知の神杖ソフィナ・ブリビア"
    private val pageTokens = real("知/0 の/1 神/2 杖/3 ソフィナ/4 ・/8 ブリ/9 ビア/11")

    @Test fun `a katakana name with a middle dot gets its face from Sudachi's pieces`() {
        val hits = FaceMatcher(mashle, { false }).match(page, pageTokens, "MASHLE")
        assertEquals(1, hits.size)
        assertEquals(4, hits[0].start)
        assertEquals(9, hits[0].length)
        assertEquals("ソフィナ・ブリビア", hits[0].name)
    }

    @Test fun `the full dotted name as one token, a piece alone, a piece split in two, a piece with an honorific`() {
        val m = FaceMatcher(mashle, { false })
        assertEquals(1, m.match("ソフィナ・ブリビア", real("ソフィナ・ブリビア/0"), "MASHLE").size)
        assertEquals("ソフィナ", m.match("ソフィナが来た", real("ソフィナ/0 が/4 来た/5"), "MASHLE").single().name)
        assertEquals("マッシュ", m.match("マッシュが来た", real("マッシュ/0 が/4 来た/5"), "MASHLE").single().name)
        val f = m.match("バーンデッドが来た", real("バーン/0 デッド/3 が/6 来た/7"), "MASHLE") // family split by Sudachi
        assertEquals("バーンデッド", f.single().name)
        val h = m.match("マッシュくん", real("マッシュ/0 くん/4"), "MASHLE")
        assertEquals("マッシュ", h.single().name)
        assertEquals(0, h[0].start)
    }

    @Test fun `a piece needs a work and must not be an ordinary word, the whole name needs neither`() {
        val one = real("マッシュ/0 が/4 来た/5")
        assertTrue(FaceMatcher(mashle, { true }).match("マッシュが来た", one, null).isEmpty()) // no work
        assertTrue(FaceMatcher(mashle, { true }).match("マッシュが来た", one, " ").isEmpty())
        assertTrue(FaceMatcher(mashle, { false }, commonWord = { true }).match("マッシュが来た", one, "MASHLE").isEmpty()) // an ordinary word
        val asked = ArrayList<String>()
        FaceMatcher(mashle, { false }, commonWord = { asked += it.joinToString("") { t -> t.surface }; false }).match("バーンデッド", real("バーン/0 デッド/3"), "MASHLE")
        assertEquals(listOf("バーンデッド"), asked) // the guard is asked about the pieces joined
        val whole = FaceMatcher(mashle, { false }, commonWord = { true })
        assertEquals(1, whole.match("ソフィナ・ブリビア", real("ソフィナ/0 ・/4 ブリ/5 ビア/7"), "MASHLE").size) // one hit, not two pieces
        assertEquals(1, FaceMatcher(mashle, { true }, commonWord = { true }).match("ソフィナ・ブリビア", real("ソフィナ/0 ・/4 ブリ/5 ビア/7"), null).size)
    }

    @Test fun `other separators are the same as the middle dot`() {
        val m = FaceMatcher(mashle, { false })
        assertEquals("ジャン＝ジャック", m.match("ジャン＝ジャック", real("ジャン/0 ＝/3 ジャック/4"), "MASHLE").single().name)
        assertEquals("ジャック", m.match("ジャックだ", real("ジャック/0 だ/4"), "MASHLE").single().name)
        assertEquals("ルーク･ガーデン", m.match("ルーク･ガーデン", real("ルーク/0 ･/3 ガーデン/4"), "MASHLE").single().name)
        assertEquals("ガーデン", m.match("ガーデンさん", real("ガーデン/0 さん/4"), "MASHLE").single().name)
        assertEquals("ミナ·ローズ", m.match("ミナ·ローズ", real("ミナ/0 ·/2 ローズ/3"), "MASHLE").single().name)
        // the page writes another separator than the card: still the same name
        assertEquals(listOf(0 to 8), m.match("ジャン・ジャック", real("ジャン/0 ・/3 ジャック/4"), "MASHLE").map { it.start to it.length })
        assertEquals(1, m.match("ジャンジャック", real("ジャンジャック/0"), "MASHLE").size) // and none at all
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
        val m = FaceMatcher(dict, cache = { k, f -> asked += k; f() })
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
