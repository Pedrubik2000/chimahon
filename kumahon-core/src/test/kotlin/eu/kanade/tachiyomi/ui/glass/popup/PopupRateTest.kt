package eu.kanade.tachiyomi.ui.glass.popup

import chimahon.srs.JdbcSqlDb
import chimahon.srs.PeerSync
import chimahon.srs.SqlDb
import chimahon.srs.Srs
import chimahon.srs.WordKey
import io.kotest.matchers.shouldBe
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PopupRateTest {
    @TempDir lateinit var dir: File
    private val open = mutableListOf<SqlDb>()
    @AfterEach fun closeAll() = open.forEach { it.close() }
    private fun store(name: String) = Srs(JdbcSqlDb(File(dir, "$name.db")).also { open += it }, "tab")

    private fun mine(s: Srs, word: String, reading: String) =
        s.addCard(WordKey.furigana(word, reading), "s", "m", null, null, null, JSONObject().put("Reading", reading).toString())

    @Test
    fun `a popup rating writes one row to the own store and asks for one sync`() {
        val own = store("own")
        val other = store("other") // stands for anything else: nothing may reach it
        val card = mine(own, "済みません", "すみません")
        var syncs = 0
        val rev = PopupRate.rate(own, card.id, 3, 1200) { syncs++ }!!
        own.lastRevlogId(card.id) shouldBe rev
        syncs shouldBe 1
        own.dump().getJSONArray("revlog").length() shouldBe 1
        other.dump().getJSONArray("revlog").length() shouldBe 0
        (own.card(card.id)!!.lastReview != null) shouldBe true
        // a rating that is not 1..4, or for no card, writes nothing and syncs nothing
        PopupRate.rate(own, card.id, 5, 0) { syncs++ } shouldBe null
        PopupRate.rate(own, 42, 3, 0) { syncs++ } shouldBe null
        syncs shouldBe 1
        own.dump().getJSONArray("revlog").length() shouldBe 1
    }

    @Test
    fun `undo takes it back and syncs`() {
        val own = store("own")
        val card = mine(own, "済みません", "すみません")
        PopupRate.undo(own, card.id, 1) { } shouldBe false // nothing to undo yet
        val rev = PopupRate.rate(own, card.id, 3, 0) { }!!
        var syncs = 0
        PopupRate.undo(own, card.id, rev) { syncs++ } shouldBe true
        syncs shouldBe 1
        own.card(card.id)!!.stability shouldBe null
    }

    @Test
    fun `undo only takes back its own rating, not a newer one`() {
        val own = store("own")
        val card = mine(own, "済みません", "すみません")
        val mineRev = PopupRate.rate(own, card.id, 3, 0) { }!!
        own.rate(card.id, 4, now = System.currentTimeMillis() + 5_000) // something else rated it after (Review, another device)
        var syncs = 0
        PopupRate.undo(own, card.id, mineRev) { syncs++ } shouldBe false
        syncs shouldBe 0
        own.dump().getJSONArray("revlog").length() shouldBe 2 // no undo row written
        (own.card(card.id)!!.stability != null) shouldBe true
    }

    @Test
    fun `a double tap rates once, a failed or undone rating gives the turn back`() {
        val once = RateOnce()
        once.begin() shouldBe true
        once.begin() shouldBe false
        once.fail()
        once.begin() shouldBe true
        once.reset()
        once.begin() shouldBe true
    }

    @Test
    fun `revlog id is milliseconds times 1000 plus 0 to 999 and the time is the real rating time`() {
        val own = store("own")
        val card = mine(own, "済みません", "すみません")
        val now = 1_764_000_000_123L
        own.rate(card.id, 3, now = now)
        val row = own.dump().getJSONArray("revlog").getJSONObject(0)
        (row.getLong("id") in now * 1000..now * 1000 + 999) shouldBe true
        row.getLong("time") shouldBe now
        (Srs.newId(now) in now * 1000..now * 1000 + 999) shouldBe true
    }

    @Test
    fun `the event key of a rated word is kumapie scheme ja then spelling tab reading`() {
        val own = store("own")
        PeerSync.keyOf(mine(own, "済みません", "すみません")) shouldBe "ja|済みません\tすみません"
        PeerSync.keyOf(mine(own, "すみません", "すみません")) shouldBe "ja|すみません\tすみません"
        // what the popup looks the card up by is the same key
        WordKey.lookupKey("済みません", "すみません") shouldBe "ja|済みません\tすみません"
        WordKey.lookupKey("すみません", "すみません") shouldBe "ja|すみません\tすみません"
    }
}
