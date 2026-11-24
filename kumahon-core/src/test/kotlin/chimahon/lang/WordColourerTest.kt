package chimahon.lang

import chimahon.lang.JapaneseModel.Token
import chimahon.srs.JdbcSqlDb
import chimahon.srs.Srs
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [WordColourer] with a fake splitter: the order of kumapie's `Word.shownFor` (mark, card, form's card, known or name, else red). */
class WordColourerTest {
    @TempDir lateinit var dir: File
    private val open = mutableListOf<JdbcSqlDb>()
    @AfterEach fun closeAll() = open.forEach { it.close() }

    private val store by lazy { Srs(JdbcSqlDb(File(dir, "s.db")).also { open += it }, "pc") }
    private val DAY = 86_400_000L

    private fun card(word: String, reading: String) =
        store.addCard("$word[$reading]", "s", "m", null, null, null, JSONObject().put("Reading", reading).toString())

    private fun word(surface: String, base: String = surface, begin: Int = 0, pos: String = "名詞") = Token(surface, base, "", pos, begin)

    private fun state(t: Token, auto: (String) -> Boolean = { false }) =
        WordColourer({ store }, { listOf(t) }, auto).rows(t.surface).single().state

    @Test fun unknownIsRed() = assertEquals("u", state(word("猫")))

    @Test fun newCardIsBlue() { card("猫", "ねこ"); assertEquals("b", state(word("猫"))) }

    @Test fun reviewedAndDueIsGreen() {
        val c = card("猫", "ねこ")
        store.rate(c.id, 3, now = System.currentTimeMillis() - 30 * DAY) // due long ago
        assertEquals("d", state(word("猫")))
    }

    @Test fun reviewedAndNotDueIsKnown() {
        val c = card("猫", "ねこ")
        store.rate(c.id, 3) // due in days
        assertEquals("k", state(word("猫")))
    }

    @Test fun markBeatsCard() {
        card("猫", "ねこ") // blue
        store.replaceMarked(setOf("猫"))
        assertEquals("k", state(word("猫")))
    }

    @Test fun cardBeatsKnownList() {
        card("猫", "ねこ")
        store.replaceKnown(setOf("猫"))
        assertEquals("b", state(word("猫")))
    }

    @Test fun knownListOrNameWhenNoCard() {
        store.replaceKnown(setOf("犬"))
        assertEquals("k", state(word("犬")))
        assertEquals("k", state(word("エリス", pos = "名詞"), auto = { it == "エリス" })) // autoKnown: names, fillers
        assertEquals("u", state(word("鳥")))
    }

    @Test fun writtenFormsCardWhenBaseHasNone() {
        card("届いた", "とどいた") // a card mined as written
        assertEquals("b", state(word("届いた", base = "届く")))
    }

    private fun colourer(split: (String) -> List<Token>?, spellings: (String, String) -> Set<String> = { _, _ -> emptySet() }) =
        WordColourer({ store }, split, { false }, spellings)

    /** kumapie `Japanese.tapped`: まひ tapped on a 麻痺 card colours まひ (it went back to red after mining, 2026-11-17). */
    @Test fun tappedKanaFormOfAKanjiCard() {
        store.addCard("麻痺[まひ]", "<b>まひ</b>した", "m", null, null, null, JSONObject().put("Reading", "まひ").toString())
        val words = mapOf("まひ" to listOf(word("まひ")))
        assertEquals("b", colourer({ words[it] ?: listOf(word(it)) }).rows("まひ").single().state)
    }

    /** `<b>ワナ</b>` on a 罠 card: ワナ is blue, and so is its dictionary form when it splits into one word (ワナ -> わな). */
    @Test fun tappedKatakanaFormOfAKanjiCard() {
        store.addCard("罠[わな]", "<b>ワナ</b>にかかる", "m", null, null, null, JSONObject().put("Reading", "わな").toString())
        val split = { t: String -> if (t == "ワナ") listOf(word("ワナ", base = "わな")) else listOf(word(t)) }
        assertEquals("b", colourer(split).rows("ワナ").single().state)
        assertEquals("b", colourer(split).rows("わな").single().state)
    }

    @Test fun boldEqualToTheCardsSpellingAddsNothing() {
        store.addCard("覚え[おぼえ]", "<b>覚え</b>", "m", null, null, null, JSONObject().put("Reading", "おぼえ").toString())
        assertEquals("u", colourer({ listOf(word("覚える")) }).rows("覚える").single().state) // 覚え's card must not colour 覚える
    }

    @Test fun otherSpellingsOfTheCardsWord() {
        card("分かる", "わかる")
        val c = colourer({ listOf(word("わかる")) }) { s, _ -> if (s == "分かる") setOf("わかる") else emptySet() }
        assertEquals("b", c.rows("わかる").single().state)
        assertEquals("u", colourer({ listOf(word("わかる")) }).rows("わかる").single().state)
    }

    @Test fun rowsAreWordsOnlyWithPositions() {
        val tokens = listOf(word("猫", begin = 0), word("、", begin = 1, pos = "補助記号"), word("犬", begin = 2))
        store.replaceKnown(setOf("犬"))
        val rows = WordColourer({ store }, { tokens }, { false }).rows("猫、犬")
        assertEquals(listOf(WordColourer.Row(0, 1, "u"), WordColourer.Row(2, 1, "k")), rows)
    }

    @Test fun noDictionaryNoColours() = assertEquals(emptyList<WordColourer.Row>(), WordColourer({ store }, { null }, { false }).rows("猫"))

    @Test fun markMadeHereShowsAtOnceAndGoesToTheHub() {
        val c = WordColourer({ store }, { listOf(word("猫")) }, { false })
        assertEquals("u", c.rows("猫").single().state)
        store.mark("猫", true, nowSecs = 100)
        c.reload()
        assertEquals("k", c.rows("猫").single().state)
        // sent as kumapie's Progress part: marked[word] = [known, seconds]
        val part = chimahon.srs.PeerSync.progress(store.myMarks(), "tab")!!.getJSONObject("tab")
        assertEquals(true, part.getJSONObject("marked").getJSONArray("猫").getBoolean(0))
        assertEquals(100L, part.getJSONObject("marked").getJSONArray("猫").getLong(1))
        store.mark("猫", false, nowSecs = 200)
        c.reload()
        assertEquals("u", c.rows("猫").single().state)
    }

    @Test fun myMarkBeatsOlderHubMarkAndLosesToNewer() {
        val hub = { at: Long, known: Boolean -> JSONObject().put("kumapie", JSONObject().put("marked", JSONObject().put("猫", org.json.JSONArray().put(known).put(at)))) }
        val mine = mapOf("猫" to (true to 100L))
        assertEquals(setOf("猫"), chimahon.srs.KnownWords.marks(hub(50, false), mine))
        assertEquals(emptySet<String>(), chimahon.srs.KnownWords.marks(hub(150, false), mine))
    }

    @Test fun reloadSeesNewMarks() {
        val c = WordColourer({ store }, { listOf(word("猫")) }, { false })
        assertEquals("u", c.rows("猫").single().state)
        store.replaceMarked(setOf("猫"))
        c.reload()
        assertEquals("k", c.rows("猫").single().state)
    }
}
