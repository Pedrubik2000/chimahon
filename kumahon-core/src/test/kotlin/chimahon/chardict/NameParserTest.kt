package chimahon.chardict

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import chimahon.chardict.NameParser.findAllSplitPoints
import chimahon.chardict.NameParser.findSplitPoint
import chimahon.chardict.NameParser.generateMixedNameReadings
import chimahon.chardict.NameParser.generateNameReadings
import chimahon.chardict.NameParser.splitJapaneseName
import chimahon.chardict.NameParser.splitJapaneseNameAllCandidates
import chimahon.chardict.NameParser.splitJapaneseNameWithHints

/** The cases of Bee's `name_parser.rs` tests (MIT, see NOTICE.md), one Kotlin test per Rust test. */
class NameParserTest {
    // === name splitting ===
    @Test fun splitWithSpace() {
        val p = splitJapaneseName("須々木 心一")
        assertTrue(p.hasSpace)
        assertEquals("須々木", p.family); assertEquals("心一", p.given)
        assertEquals("須々木心一", p.combined); assertEquals("須々木 心一", p.original)
    }

    @Test fun splitNoSpace() {
        val p = splitJapaneseName("single")
        assertFalse(p.hasSpace); assertNull(p.family); assertNull(p.given); assertEquals("single", p.combined)
    }

    @Test fun splitEmpty() {
        val p = splitJapaneseName("")
        assertFalse(p.hasSpace); assertEquals("", p.combined)
    }

    @Test fun splitMultipleSpaces() {
        val p = splitJapaneseName("A B C")
        assertTrue(p.hasSpace); assertEquals("A", p.family); assertEquals("B C", p.given)
    }

    @Test fun splitMiddleDotIsNotASpace() {
        val p = splitJapaneseName("ルルーシュ・ランペルージ")
        assertFalse(p.hasSpace); assertEquals("ルルーシュ・ランペルージ", p.combined)
    }

    @Test fun splitSingleSpace() {
        val p = splitJapaneseName(" ")
        assertTrue(p.hasSpace); assertEquals("", p.family); assertEquals("", p.given)
    }

    // === mixed (no hints) readings ===
    @Test fun mixedReadingsEmpty() {
        val r = generateMixedNameReadings("", "")
        assertEquals(NameReadings("", "", ""), r)
    }

    @Test fun mixedReadingsSingleKanji() = assertEquals(Kana.alphabetToKana("kan"), generateMixedNameReadings("漢", "Kan").full)
    @Test fun mixedReadingsSingleKana() = assertEquals("あいう", generateMixedNameReadings("あいう", "unused").full)
    @Test fun mixedReadingsSingleKatakana() = assertEquals("あいう", generateMixedNameReadings("アイウ", "unused").full)

    @Test fun mixedReadingsTwoPartBothKanji() {
        val r = generateMixedNameReadings("漢 字", "Given Family")
        assertEquals(Kana.alphabetToKana("given"), r.family); assertEquals(Kana.alphabetToKana("family"), r.given)
    }

    @Test fun mixedReadingsTwoPartMixed() {
        val r = generateMixedNameReadings("漢 かな", "Romaji Unused")
        assertEquals(Kana.alphabetToKana("romaji"), r.family); assertEquals("かな", r.given)
    }

    @Test fun mixedReadingsTwoPartAllKana() {
        val r = generateMixedNameReadings("あい うえ", "Unused Unused2")
        assertEquals("あい", r.family); assertEquals("うえ", r.given); assertEquals("あいうえ", r.full)
    }

    @Test fun mixedReadingsKanjiWithEmptyRomanized() = assertEquals("", generateMixedNameReadings("漢字", "").full)

    @Test fun mixedReadingsTwoPartKanjiSingleWordRomanized() {
        val r = generateMixedNameReadings("漢 字", "SingleWord")
        assertEquals(Kana.alphabetToKana("singleword"), r.family); assertEquals("", r.given)
    }

    @Test fun mixedReadingsRomanizedHasExtraSpaces() {
        val r = generateMixedNameReadings("漢 字", "Given  Family")
        assertEquals(Kana.alphabetToKana("given"), r.family); assertEquals(Kana.alphabetToKana(" family"), r.given)
    }

    @Test fun mixedReadingsApostropheInRomanized() {
        val r = generateMixedNameReadings("岡部 倫太郎", "Rintarou Okabe")
        assertEquals("りんたろう", r.family); assertEquals("おかべ", r.given)
    }

    @Test fun mixedReadingsApostropheDisambiguation() {
        val r = generateMixedNameReadings("工藤 新一", "Shin'ichi Kudou")
        assertEquals("しんいち", r.family); assertEquals("くどう", r.given)
    }

    // === honorific table ===
    @Test fun honorificSuffixesSubstantial() {
        assertTrue(HONORIFIC_SUFFIXES.size > 200, "Expected 200+ honorifics, got ${HONORIFIC_SUFFIXES.size}")
    }

    @Test fun honorificSuffixesContainCommon() {
        val displays = HONORIFIC_SUFFIXES.map { it.first }
        for (s in listOf("さん", "ちゃん", "くん", "君", "ちょ", "ちょる", "様", "先生", "先輩")) assertTrue(s in displays, "Missing $s")
    }

    @Test fun honorificSuffixesHaveAllThreeFields() {
        for ((d, r, desc) in HONORIFIC_SUFFIXES) {
            assertTrue(d.isNotEmpty() && r.isNotEmpty() && desc.isNotEmpty(), "empty field in $d")
        }
    }

    @Test fun honorificSuffixesUniqueTriples() {
        val seen = HashSet<Triple<String, String, String>>()
        for (t in HONORIFIC_SUFFIXES) assertTrue(seen.add(t), "Duplicate honorific triple: $t")
    }

    // === generateNameReadings ===
    @Test fun readingsNoHintsDelegatesToVndbBehavior() {
        val r = generateNameReadings("須々木 心一", "Shinichi Suzuki", null, null)
        assertEquals(Kana.alphabetToKana("shinichi"), r.family); assertEquals(Kana.alphabetToKana("suzuki"), r.given)
    }

    @Test fun readingsEmptyNative() = assertTrue(generateNameReadings("", "Some Name", "Some", "Name").full.isEmpty())

    @Test fun readingsHintsWithSpace() {
        val r = generateNameReadings("田所 恵", "Megumi Tadokoro", "Megumi", "Tadokoro")
        assertEquals("たどころ", r.family); assertEquals("めぐみ", r.given)
    }

    @Test fun readingsHintsNoSpaceKanji() {
        val r = generateNameReadings("幸平創真", "Souma Yukihira", "Souma", "Yukihira")
        assertEquals("ゆきひら", r.family); assertEquals("そうま", r.given)
    }

    @Test fun readingsHintsKatakanaGiven() {
        val r = generateNameReadings("薙切アリス", "Alice Nakiri", "Alice", "Nakiri")
        assertEquals("なきり", r.family); assertEquals("ありす", r.given)
    }

    @Test fun readingsHintsMiddleDot() =
        assertEquals("たくみ・あるでぃーに", generateNameReadings("タクミ・アルディーニ", "Takumi Aldini", "Takumi", "Aldini").full)

    @Test fun readingsSingleNameOnlyFirst() = assertEquals("ひみこ", generateNameReadings("ヒミコ", "Himiko", "Himiko", null).full)
    @Test fun readingsSingleKanjiOnlyFirst() = assertEquals("とくぞう", generateNameReadings("徳蔵", "Tokuzou", "Tokuzou", null).full)

    @Test fun readingsTrimWhitespace() {
        val r = generateNameReadings("佐藤昭二", "Shouji Satou", "Shouji ", " Satou ")
        assertEquals("さとう", r.family); assertEquals("しょうじ", r.given)
    }

    @Test fun readingsEmptyLastHintIsNone() =
        assertTrue(generateNameReadings("田所の母", "Tadokoro no Haha", "Tadokoro no Haha", "").full.isNotEmpty())

    @Test fun readingsVndbMixedKanjiKana() {
        val r = generateNameReadings("千俵 おりえ", "Senpyou Orie", null, null)
        assertEquals("せんぴょう", r.family); assertEquals("おりえ", r.given)
    }

    @Test fun readingsVndbPureKatakana() = assertEquals("せいばー", generateNameReadings("セイバー", "Saber", null, null).full)

    @Test fun readingsAnilistHintsSpaced() {
        val r = generateNameReadings("岡部 倫太郎", "Okabe Rintarou", "Rintarou", "Okabe")
        assertEquals("おかべ", r.family); assertEquals("りんたろう", r.given)
    }

    @Test fun readingsAnilistHintsNoSpace() {
        val r = generateNameReadings("幸平創真", "Souma Yukihira", "Souma", "Yukihira")
        assertEquals("ゆきひら", r.family); assertEquals("そうま", r.given); assertEquals("ゆきひらそうま", r.full)
    }

    @Test fun readingsKatakanaMiddleDotWithHints() =
        assertEquals("るるーしゅ・らんぺるーじ", generateNameReadings("ルルーシュ・ランペルージ", "Lelouch Lamperouge", "Lelouch", "Lamperouge").full)

    @Test fun readingsSingleKanjiWithFirstHintOnly() = assertEquals("まもる", generateNameReadings("守", "Mamoru", "Mamoru", null).full)

    @Test fun readingsEmptyHintsTreatedAsNone() {
        val r = generateNameReadings("岡部 倫太郎", "Okabe Rintarou", "", "")
        assertEquals("おかべ", r.family); assertEquals("りんたろう", r.given)
    }

    @Test fun readingsWhitespaceOnlyHintsTreatedAsNone() {
        val r = generateNameReadings("岡部 倫太郎", "Okabe Rintarou", "  ", "  ")
        assertEquals("おかべ", r.family); assertEquals("りんたろう", r.given)
    }

    @Test fun readingsEmiyaShirou() {
        val r = generateNameReadings("衛宮士郎", "Shirou Emiya", "Shirou", "Emiya")
        assertEquals("えみや", r.family); assertEquals("しろう", r.given)
    }

    @Test fun readingsToosakaRin() {
        val r = generateNameReadings("遠坂 凛", "Rin Toosaka", "Rin", "Toosaka")
        assertEquals("とおさか", r.family); assertEquals("りん", r.given)
    }

    @Test fun readingsMatouSakura() {
        val r = generateNameReadings("間桐 桜", "Sakura Matou", "Sakura", "Matou")
        assertEquals("まとう", r.family); assertEquals("さくら", r.given)
    }

    @Test fun readingsVndbApostropheName() {
        val r = generateNameReadings("須々木 心一", "Suzuki Shin'ichi", null, null)
        assertEquals("すずき", r.family); assertEquals("しんいち", r.given)
    }

    @Test fun readingsKominatoAya() {
        val r = generateNameReadings("小湊亜耶", "Aya Kominato", "Aya", "Kominato")
        assertEquals("こみなと", r.family); assertEquals("あや", r.given); assertEquals("こみなとあや", r.full)
    }

    @Test fun readingsIshiiMamoru() {
        val r = generateNameReadings("石井守", "Mamoru Ishii", "Mamoru", "Ishii")
        assertEquals("いしい", r.family); assertEquals("まもる", r.given); assertEquals("いしいまもる", r.full)
    }

    @Test fun readingsConsistentWithAndWithoutHints() {
        val r1 = generateNameReadings("岡部 倫太郎", "Okabe Rintarou", null, null)
        val r2 = generateNameReadings("岡部 倫太郎", "Okabe Rintarou", "Rintarou", "Okabe")
        assertEquals(r1, r2)
    }

    @Test fun readingsHaveNoInternalWhitespace() {
        val r = generateNameReadings("イレイナの母", "Elaina no Haha", null, null)
        assertFalse(' ' in r.full || ' ' in r.family || ' ' in r.given, r.toString())
        val r2 = generateNameReadings("須々木 心一", "Shinichi Suzuki", "Shinichi", "Suzuki")
        assertFalse(' ' in r2.full)
    }

    // === splitJapaneseNameWithHints ===
    @Test fun splitHintsNoHintsDelegates() {
        val p = splitJapaneseNameWithHints("須々木 心一", null, null)
        assertTrue(p.hasSpace); assertEquals("須々木", p.family); assertEquals("心一", p.given)
    }

    @Test fun splitHintsWithSpaceUsesSpace() {
        val p = splitJapaneseNameWithHints("千俵 おりえ", "Orie", "Sendawara")
        assertTrue(p.hasSpace); assertEquals("千俵", p.family); assertEquals("おりえ", p.given)
    }

    @Test fun splitHintsMiddleDotNotSplit() {
        val p = splitJapaneseNameWithHints("ローランド・シャペル", "Roland", "Chapelle")
        assertFalse(p.hasSpace); assertNull(p.family)
    }

    @Test fun splitHintsEmptyLastNoSplit() {
        val p = splitJapaneseNameWithHints("田所の母", "Tadokoro no Haha", "")
        assertFalse(p.hasSpace); assertNull(p.family)
    }

    @Test fun splitHintsKanjiNoSpaceProducesParts() {
        val p = splitJapaneseNameWithHints("幸平創真", "Souma", "Yukihira")
        assertNotNull(p.family); assertNotNull(p.given); assertEquals("幸平創真", p.combined)
    }

    @Test fun splitHintsMixedKanaKanji() {
        val p = splitJapaneseNameWithHints("薙切えりな", "Erina", "Nakiri")
        assertEquals("薙切", p.family); assertEquals("えりな", p.given)
    }

    @Test fun splitHintsIshiiMamoru() {
        val p = splitJapaneseNameWithHints("石井守", "Mamoru", "Ishii")
        assertNotNull(p.family); assertNotNull(p.given); assertEquals("石井守", p.combined)
    }

    @Test fun splitHintsThreeKanjiName() = assertEquals("田中太", splitJapaneseNameWithHints("田中太", "Futo", "Tanaka").combined)
    @Test fun splitHintsAllHiraganaNoSpace() = assertEquals("たなかたろう", splitJapaneseNameWithHints("たなかたろう", "Tarou", "Tanaka").combined)
    @Test fun splitHintsKatakanaNoMiddleDot() = assertEquals("セイバーアルトリア", splitJapaneseNameWithHints("セイバーアルトリア", "Artoria", "Saber").combined)

    @Test fun splitPreservesOriginalText() {
        val p = splitJapaneseNameWithHints("岡部 倫太郎", null, null)
        assertEquals("岡部 倫太郎", p.original); assertEquals("岡部倫太郎", p.combined); assertTrue(p.hasSpace)
    }

    @Test fun splitNoSpaceNoHintsReturnsUnsplit() {
        val p = splitJapaneseNameWithHints("岡部倫太郎", null, null)
        assertFalse(p.hasSpace); assertNull(p.family); assertNull(p.given)
        assertEquals("岡部倫太郎", p.original); assertEquals("岡部倫太郎", p.combined)
    }

    @Test fun splitHintsKominatoAya() {
        val p = splitJapaneseNameWithHints("小湊亜耶", "Aya", "Kominato")
        assertEquals("小湊", p.family, "Family should be 小湊"); assertEquals("亜耶", p.given, "Given should be 亜耶")
    }

    // === findSplitPoint / findAllSplitPoints ===
    @Test fun findSplitKanjiKanaBoundary() = assertEquals("薙切" to "アリス", findSplitPoint("薙切アリス", "なきり", "ありす"))
    @Test fun findSplitAllKanji() = assertEquals("幸平" to "創真", findSplitPoint("幸平創真", "ゆきひら", "そうま"))
    @Test fun findSplitSingleChar() = assertNull(findSplitPoint("漢", "かん", ""))
    @Test fun findSplitEmpty() = assertNull(findSplitPoint("", "かん", "じ"))
    @Test fun findSplitIshiiMamoruHasAnswer() = assertNotNull(findSplitPoint("石井守", "いしい", "まもる"))
    @Test fun findSplitKominatoAya() = assertEquals("小湊" to "亜耶", findSplitPoint("小湊亜耶", "こみなと", "あや"))

    @Test fun findAllSplitsIshiiMamoruReturnsBoth() {
        val r = findAllSplitPoints("石井守", "いしい", "まもる")
        assertEquals(2, r.size, "Should return two tied splits")
        assertTrue(r.map { it.first }.containsAll(listOf("石", "石井")))
        assertTrue(r.map { it.second }.containsAll(listOf("井守", "守")))
    }

    @Test fun findAllSplitsUnambiguousReturnsOne() {
        val r = findAllSplitPoints("幸平創真", "ゆきひら", "そうま")
        assertEquals(listOf("幸平" to "創真"), r)
    }

    @Test fun findAllSplitsKanjiKanaBoundaryReturnsOne() = assertEquals(listOf("薙切" to "アリス"), findAllSplitPoints("薙切アリス", "なきり", "ありす"))

    // === splitJapaneseNameAllCandidates ===
    @Test fun allCandidatesIshiiMamoru() {
        val c = splitJapaneseNameAllCandidates("石井守", "Mamoru", "Ishii")
        assertEquals(2, c.size)
        assertTrue(c.map { it.family }.containsAll(listOf("石", "石井")))
    }

    @Test fun allCandidatesWithSpaceReturnsOne() {
        val c = splitJapaneseNameAllCandidates("田所 恵", "Megumi", "Tadokoro")
        assertEquals(1, c.size); assertEquals("田所", c[0].family)
    }

    @Test fun allCandidatesEmptyName() {
        val c = splitJapaneseNameAllCandidates("", "A", "B")
        assertEquals(1, c.size); assertNull(c[0].family)
    }

    @Test fun allCandidatesNoHints() {
        val c = splitJapaneseNameAllCandidates("田中太郎", null, null)
        assertEquals(1, c.size); assertNull(c[0].family)
    }

    @Test fun allCandidatesWithSpace() {
        val c = splitJapaneseNameAllCandidates("田中 太郎", "Tarou", "Tanaka")
        assertEquals(1, c.size); assertTrue(c[0].hasSpace); assertEquals("田中", c[0].family); assertEquals("太郎", c[0].given)
    }

    @Test fun allCandidatesMiddleDot() {
        val c = splitJapaneseNameAllCandidates("ルルーシュ・ランペルージ", "Lelouch", "Lamperouge")
        assertEquals(1, c.size); assertFalse(c[0].hasSpace)
    }
}
