package chimahon.chardict

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** The zip structure Bee's `dict_builder.rs` produces, read the way kumapie's Hoshidicts reads it. */
class YomitanCharacterZipTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    private fun souma(role: String = "primary", id: String = "1") = Character(
        id = id, name = "Souma Yukihira", nameOriginal = "幸平創真", role = role,
        firstNameHint = "Souma", lastNameHint = "Yukihira",
        aliases = listOf("Aoki Umi (碧井海)", "Soma"), spoilerAliases = listOf("秘密の名前"),
        description = "A cook. ~!He wins!~ [b]Bold[/b]", sex = "m", age = "16", birthday = listOf(9, 1), bloodType = "A",
        imageBytes = jpeg, imageExt = "jpg", imageWidth = 80, imageHeight = 100,
        seiyuu = "松岡禎丞",
    )

    private class Out(val files: LinkedHashMap<String, ByteArray>, val methods: Map<String, Int>) {
        fun json(name: String) = String(files.getValue(name), Charsets.UTF_8)
        val banks get() = files.keys.filter { it.startsWith("term_bank_") }
        val terms: List<JSONArray> get() = banks.flatMap { b -> JSONArray(json(b)).let { a -> (0 until a.length()).map { a.getJSONArray(it) } } }
        fun entry(term: String): List<JSONArray> = terms.filter { it.getString(0) == term }
    }

    private fun write(zip: YomitanCharacterZip): Out {
        val buf = ByteArrayOutputStream()
        zip.write(buf)
        val files = LinkedHashMap<String, ByteArray>()
        val methods = HashMap<String, Int>()
        ZipInputStream(ByteArrayInputStream(buf.toByteArray())).use { z ->
            var e: ZipEntry? = z.nextEntry
            while (e != null) {
                files[e.name] = z.readBytes()
                methods[e.name] = e.method
                e = z.nextEntry
            }
        }
        return Out(files, methods)
    }

    private fun build(settings: DictSettings = DictSettings(), vararg chars: Pair<Character, String>): Out {
        val zip = YomitanCharacterZip(settings, revision = "20261124_1200")
        for ((c, title) in chars) zip.addCharacter(c, title)
        return write(zip)
    }

    @Test fun zipHasIndexTagBankTermBankAndImages() {
        val out = build(DictSettings(), souma() to "食戟のソーマ")
        assertEquals(listOf("index.json", "tag_bank_1.json", "term_bank_1.json"), out.files.keys.take(3))
        assertEquals("img/c1.jpg", out.files.keys.last()) // images last
        val index = JSONObject(out.json("index.json"))
        assertEquals("kumahon Characters (books & manga)", index.getString("title"))
        assertEquals("20261124_1200", index.getString("revision"))
        assertEquals(3, index.getInt("format"))
        assertTrue("character" in index.getString("title").lowercase()) // Hoshidicts.isCharacter: the title says "character"
        assertFalse(index.has("downloadUrl")) // no 127.0.0.1 links on a phone
        val tags = JSONArray(out.json("tag_bank_1.json"))
        assertEquals("name", tags.getJSONArray(0).getString(0))
        assertEquals(setOf("name", "main", "primary", "side", "appears", "dict-built"), (0 until tags.length()).map { tags.getJSONArray(it).getString(0) }.toSet())
        assertTrue(out.files.getValue("img/c1.jpg").contentEquals(jpeg))
        assertEquals(java.util.zip.ZipEntry.STORED, out.methods["img/c1.jpg"])
    }

    @Test fun theCharacterEntryHasReadingRoleScoreAndTheCard() {
        val out = build(DictSettings(), souma() to "食戟のソーマ")
        val e = out.entry("幸平創真").single()
        assertEquals(8, e.length())
        assertEquals("ゆきひらそうま", e.getString(1))
        assertEquals("name primary", e.getString(2)) // tags
        assertEquals("", e.getString(3))
        assertEquals(75, e.getInt(4)) // primary's score
        assertEquals(0, e.getInt(6)); assertEquals("", e.getString(7))
        val sc = e.getJSONArray(5).getJSONObject(0)
        assertEquals("structured-content", sc.getString("type"))
        val content = sc.getJSONArray("content")
        fun id(i: Int) = content.getJSONObject(i).optJSONObject("data")?.optString("id")
        assertEquals(listOf("name", "name-romaji", "portrait-container", "source", "role-container"), (0..4).map(::id))
        assertEquals("幸平創真", content.getJSONObject(0).getString("content"))
        assertEquals("Souma Yukihira", content.getJSONObject(1).getString("content"))
        val img = content.getJSONObject(2).getJSONObject("content")
        assertEquals("img", img.getString("tag")); assertEquals("img/c1.jpg", img.getString("path"))
        assertEquals(80, img.getInt("width")); assertEquals(100, img.getInt("height")); assertEquals("px", img.getString("sizeUnits"))
        assertEquals("From: 食戟のソーマ", content.getJSONObject(3).getString("content"))
        assertEquals("Main Character", content.getJSONObject(4).getJSONObject("content").getString("content"))
        // folded sections: description, character information, voiced by
        val summaries = (5 until content.length()).map { content.getJSONObject(it).getJSONArray("content").getJSONObject(0).getString("content") }
        assertEquals(listOf("Description", "Character Information", "Voiced by"), summaries)
    }

    @Test fun nameSplitKanaAndAliasEntries() {
        val out = build(DictSettings(honorifics = false, showSpoilers = true), souma() to "食戟のソーマ")
        val byTerm = out.terms.associate { it.getString(0) to it.getString(1) }
        assertEquals("ゆきひらそうま", byTerm["幸平創真"])
        assertEquals("ゆきひら", byTerm["幸平"]); assertEquals("そうま", byTerm["創真"])
        assertEquals("ゆきひらそうま", byTerm["ゆきひらそうま"]); assertEquals("ゆきひらそうま", byTerm["ゆきひら そうま"])
        assertEquals("ゆきひら", byTerm["ゆきひら"]); assertEquals("そうま", byTerm["そうま"])
        assertEquals("ゆきひらそうま", byTerm["ユキヒラソウマ"])
        assertEquals("ゆきひら", byTerm["ユキヒラ"]); assertEquals("そうま", byTerm["ソウマ"])
        assertEquals("あおきうみ", byTerm["碧井海"]) // "Aoki Umi (碧井海)": the term inside, the reading from outside
        assertEquals("ゆきひらそうま", byTerm["秘密の名前"]) // spoiler alias (spoilers on), the name's reading
        assertFalse("Soma" in byTerm) // no Japanese in it
        assertEquals(out.terms.size, out.terms.map { it.getString(0) }.toSet().size, "no term twice")
    }

    @Test fun honorificVariantsCarryTheirBannerAndReading() {
        val out = build(DictSettings(), souma() to "食戟のソーマ")
        val san = out.entry("幸平創真さん").single()
        assertEquals("ゆきひらそうまさん", san.getString(1))
        assertEquals("name primary", san.getString(2)); assertEquals(75, san.getInt(4))
        val content = san.getJSONArray(5).getJSONObject(0).getJSONArray("content")
        val banner = content.getJSONObject(0)
        assertFalse(banner.has("data"))
        assertEquals("さん", banner.getJSONArray("content").getJSONObject(0).getString("content"))
        assertEquals(" — Generic polite suffix (Mr./Ms./Mrs.)", banner.getJSONArray("content").getJSONObject(1).getString("content"))
        assertEquals("name", content.getJSONObject(1).getJSONObject("data").getString("id")) // then the same card
        // family, given, kana and alias forms get theirs too
        for (t in listOf("幸平ちゃん", "創真君", "そうまくん", "ソウマ先生", "碧井海様")) assertTrue(out.entry(t).isNotEmpty(), t)
        assertEquals("あおきうみさま", out.entry("碧井海様").single().getString(1))
        assertEquals("ゆきひらそうまさま", out.entry("幸平創真様").single().getString(1))
        // 269 suffixes on every base form: far more entries than the base ones
        assertTrue(out.terms.size > 1000, "${out.terms.size}")
        assertEquals(out.terms.size, out.terms.map { it.getString(0) }.toSet().size, "no term twice")
    }

    @Test fun hoshidictsReadsTheEntries() {
        val out = build(DictSettings(), souma() to "くま クマ 熊 ベアー")
        val plain = out.entry("幸平創真").single().getJSONArray(5).toString()
        assertEquals("Souma Yukihira" to null, character(plain))
        assertEquals("Souma Yukihira" to "さん", character(out.entry("幸平創真さん").single().getJSONArray(5).toString()))
        assertEquals("Souma Yukihira" to "様", character(out.entry("幸平創真様").single().getJSONArray(5).toString()))
        assertTrue(fromWork(plain, "くま クマ 熊 ベアー17"))
        assertFalse(fromWork(plain, "別の作品"))
        assertEquals("img/c1.jpg", portraitPath(plain))
    }

    // Hoshidicts.character / fromWork / portraitPath, as kumapie-tv's code reads a glossary
    private fun character(glossary: String): Pair<String, String?>? {
        if ("\"name-romaji\"" !in glossary) return null
        val top = JSONArray(glossary).optJSONObject(0)?.optJSONArray("content") ?: return null
        val parts = (0 until top.length()).mapNotNull { top.optJSONObject(it) }
        val romaji = parts.firstOrNull { it.optJSONObject("data")?.optString("id") == "name-romaji" }?.optString("content")
        return romaji?.let { it to parts.firstOrNull()?.takeIf { p -> !p.has("data") }?.optJSONArray("content")?.optJSONObject(0)?.optString("content") }
    }

    private fun workKey(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }

    private fun fromWork(glossary: String, work: String): Boolean {
        val w = workKey(work).trimEnd { it.isDigit() }
        if (w.length < 2) return false
        return Regex("""From: ([^"\\]+)""").findAll(glossary).any { m -> workKey(m.groupValues[1]).let { f -> f.length >= 2 && (f in w || w in f) } }
    }

    private fun portraitPath(glossary: String): String? =
        if ("\"portrait-container\"" !in glossary) null else Regex(""""path"\s*:\s*"([^"]+)"""").find(glossary)?.groupValues?.get(1)

    @Test fun termBanksHoldAtMost2000EntriesAndNothingIsLost() {
        val chars = (1..12).map { i ->
            Character(id = "$i", name = "Name$i", nameOriginal = "名前${('一'.code + i).toChar()}${('二'.code + i).toChar()}", role = "side") to "T"
        }
        val out = build(DictSettings(), *chars.toTypedArray())
        assertTrue(out.banks.size > 1, "expected several banks, got ${out.banks.size}")
        assertEquals((1..out.banks.size).map { "term_bank_$it.json" }, out.banks)
        for (b in out.banks.dropLast(1)) assertEquals(2000, JSONArray(out.json(b)).length())
        assertTrue(JSONArray(out.json(out.banks.last())).length() in 1..2000)
    }

    @Test fun theSameCharacterInTwoMediaIsOneEntryWithTwoRows() {
        val out = build(DictSettings(honorifics = false), souma("side") to "Manga A", souma("primary") to "Anime B")
        val e = out.entry("幸平創真").single()
        assertEquals(75, e.getInt(4)); assertEquals("name primary", e.getString(2)) // the best role
        val content = e.getJSONArray(5).getJSONObject(0).getJSONArray("content")
        val rows = (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optJSONObject("data")?.optString("id") == "role-container" }
        assertEquals(2, rows.size)
        assertEquals("Main Character", rows[0].getJSONArray("content").getJSONObject(0).getString("content")) // best role first
        assertEquals(" From: Anime B", rows[0].getJSONArray("content").getJSONObject(1).getString("content"))
        assertEquals(" From: Manga A", rows[1].getJSONArray("content").getJSONObject(1).getString("content"))
        assertEquals(1, out.files.keys.count { it.startsWith("img/") })
    }

    @Test fun sameNameWithAnotherIdIsMergedByName() {
        val out = build(DictSettings(honorifics = false), souma(id = "1") to "A", souma(id = "2").copy(nameOriginal = "幸平創真", imageBytes = null) to "B")
        // as Bee's code does (its doc comment says otherwise): one name, one entry
        assertEquals(1, out.entry("幸平創真").size)
    }

    @Test fun charactersWithoutAJapaneseNameAreLeftOut() {
        val zip = YomitanCharacterZip(DictSettings(honorifics = false))
        zip.addCharacter(Character(id = "9", name = "Nameless", nameOriginal = "", role = "side"), "Title X")
        zip.addCharacter(Character(id = "10", name = "Nameless 2", nameOriginal = "", role = "side"), "Title X")
        assertFalse(zip.hasEntries)
        assertEquals(mapOf("Title X" to 2), zip.skippedNoJapaneseSummary)
        val out = write(zip)
        assertEquals(listOf("index.json", "tag_bank_1.json"), out.files.keys.toList()) // an empty dictionary is still a valid zip
    }

    @Test fun singleWordNameHasNoFamilyGivenSplit() {
        val out = build(DictSettings(honorifics = false), Character(id = "5", name = "Saber", nameOriginal = "セイバー", role = "main") to "Fate")
        assertEquals(listOf("セイバー", "せいばー"), out.terms.map { it.getString(0) })
        assertEquals("せいばー", out.entry("セイバー").single().getString(1))
        assertEquals("name main", out.entry("セイバー").single().getString(2)); assertEquals(100, out.entry("セイバー").single().getInt(4))
    }

    @Test fun settingsSwitchSectionsOff() {
        val c = souma().copy(seiyuuImageBytes = jpeg, seiyuuImageExt = "jpg")
        val noImage = build(DictSettings(showImage = false, showSeiyuu = false), c to "T")
        assertFalse(noImage.files.keys.any { it.startsWith("img/") })
        assertFalse("portrait-container" in noImage.json("term_bank_1.json"))

        val bare = build(DictSettings(showTag = false, showDescription = false, showTraits = false, showSeiyuu = false, honorifics = false), c to "T")
        val e = bare.entry("幸平創真").single()
        assertEquals("name", e.getString(2)) // no role tag
        val ids = e.getJSONArray(5).toString()
        assertFalse("role-container" in ids || "Description" in ids || "Character Information" in ids || "Voiced by" in ids)
        assertFalse("さん" in bare.terms.map { it.getString(0) }.joinToString(""))
        val tags = JSONArray(bare.json("tag_bank_1.json"))
        assertEquals(setOf("name", "dict-built"), (0 until tags.length()).map { tags.getJSONArray(it).getString(0) }.toSet())

        val noSpoilers = build(DictSettings(showSpoilers = false, honorifics = false), c to "T")
        assertTrue(noSpoilers.entry("秘密の名前").isEmpty())
        assertFalse("He wins" in noSpoilers.json("term_bank_1.json")) // ~!..!~ stripped from the description
        assertTrue("He wins" in build(DictSettings(honorifics = false, showSpoilers = true), c to "T").json("term_bank_1.json"))
    }

    @Test fun noSpoilersByDefault() {
        assertFalse(DictSettings().showSpoilers)
        val out = build(DictSettings(honorifics = false), souma() to "T") // spoiler alias 秘密の名前, "~!He wins!~" in the description
        assertTrue(out.entry("秘密の名前").isEmpty())
        val all = out.banks.joinToString("") { out.json(it) }
        assertFalse("He wins" in all || "秘密の名前" in all || "~!" in all)
        assertTrue("A cook." in all) // the rest of the description stays
        assertTrue(out.entry("碧井海").isNotEmpty()) // plain aliases stay
    }

    @Test fun seiyuuPortraitGetsItsOwnFile() {
        val c = souma().copy(seiyuuImageBytes = byteArrayOf(9, 9), seiyuuImageExt = "jpg", seiyuuImageWidth = 40, seiyuuImageHeight = 60)
        val out = build(DictSettings(honorifics = false), c to "T")
        assertTrue(out.files.keys.containsAll(listOf("img/c1.jpg", "img/c1_va.jpg")))
    }

    @Test fun bareConsortNamesAreAddedWhereTheDictionaryHasNone() {
        val consort = Character(id = "7", name = "Gyokuyou Hi", nameOriginal = "玉葉妃", role = "side")
        val out = build(DictSettings(), consort to "薬屋のひとりごと")
        assertTrue(out.entry("玉葉").isNotEmpty() && out.entry("玉葉さん").isNotEmpty() && out.entry("玉葉様").isNotEmpty())
        val last = out.banks.last()
        assertTrue(JSONArray(out.json(last)).let { a -> (0 until a.length()).any { a.getJSONArray(it).getString(0) == "玉葉" } }, "in the last bank")
        assertEquals(out.entry("玉葉妃").single().getString(1), out.entry("玉葉").single().getString(1)) // the same entry otherwise
        // a dictionary that already has 玉葉 gets no second one
        val both = build(DictSettings(honorifics = false), consort to "T", Character(id = "8", name = "Gyokuyou", nameOriginal = "玉葉", role = "side") to "T")
        assertEquals(1, both.entry("玉葉").size)
    }

    @Test fun altNameFormat() {
        assertEquals("碧井海" to "あおきうみ", YomitanCharacterZip.parseAltNameFormat("Aoki Umi (碧井海)"))
        assertEquals("碧井海" to null, YomitanCharacterZip.parseAltNameFormat("碧井海"))
        assertEquals("Ruri's Mother" to null, YomitanCharacterZip.parseAltNameFormat("Ruri's Mother"))
        assertEquals("Mr (Boss)" to null, YomitanCharacterZip.parseAltNameFormat("Mr (Boss)"))
    }

    @Test fun writeFileLeavesNoTmpAndReplaces() {
        val dir = kotlin.io.path.createTempDirectory("chardict").toFile()
        try {
            val f = java.io.File(dir, "bee_reading_x.zip")
            val z = YomitanCharacterZip(DictSettings(honorifics = false)).also { it.addCharacter(souma(), "T") }
            f.writeText("old")
            z.writeFile(f) // replaces the old file
            assertTrue(f.length() > 3)
            assertEquals(listOf("bee_reading_x.zip"), dir.list()!!.toList())
            assertNull(dir.listFiles { x -> x.name.endsWith(".tmp") }!!.firstOrNull())
        } finally {
            dir.deleteRecursively()
        }
    }
}
