package chimahon.srs

import chimahon.lang.JitenKnown
import chimahon.lang.JitenReply
import chimahon.lang.KnownImport
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/** The Jiten layer next to kumapie's list and the marks: local, added up, and shared with the hub as a delta. */
class JitenLayersTest {
    @TempDir lateinit var dir: File
    private val open = mutableListOf<SqlDb>()
    @AfterEach fun closeAll() = open.forEach { it.close() }
    private fun store(name: String) = Srs(JdbcSqlDb(File(dir, "$name.db")).also { open += it }, name)
    private val s by lazy { store("j") }

    private fun imp(vararg w: String) = KnownImport(0, w.toSet())

    /** kumapie's hub for `known.ja`: the newer time replaces the whole list; a sync sends our pending list first, then takes the answer. */
    private class Hub(var time: Long, var words: Set<String>) {
        fun sync(s: Srs) {
            s.pendingShared()?.let { (t, w) -> if (t > time) { time = t; words = w.toSet() } }
            s.takeShared(time, words)
        }
    }

    @Test fun layersCountEachWordOnceAndTotalIsTheColourersUnion() {
        assertEquals(JitenState(0, 0, emptyList(), 0), s.jitenImport())
        s.takeShared(0, emptySet())
        s.replaceKnown(setOf("犬", "猫\tねこ"))
        s.replaceJiten(KnownImport(77, setOf("犬", "鳥", "は")), 10000, listOf("particles", "counters"))
        s.replaceMarked(setOf("空", "鳥"))
        assertEquals(JitenState(77, 10000, listOf("particles", "counters"), 3), s.jitenImport())
        val l = s.knownLayers()
        assertEquals(KnownLayers(2, 2, 1, 5), l) // kumapie 犬, 猫\tねこ; Jiten not in it: 鳥, は; marks in neither: 空
        assertEquals(l.total, s.knownKeys().size)
        assertTrue(s.isKnown("鳥", "とり") && s.isKnown("猫", "ねこ") && s.isKnownSpelling("は") && s.isKnownSpelling("空"))
        s.replaceKnown(emptySet()) // a sync
        val after = s.knownLayers()
        assertEquals(KnownLayers(0, 3, 1, 4), after)
        assertEquals(after.total, s.knownKeys().size)
    }

    @Test fun aWordMarkedUnknownHereLeavesEveryLayerAndNoSyncSendsTheMark() {
        s.takeShared(0, emptySet())
        s.replaceKnown(setOf("犬"))
        s.replaceJiten(KnownImport(5, setOf("犬", "鳥")), 0, emptyList())
        s.mark("犬", false)
        assertFalse(s.isKnownSpelling("犬"))
        assertEquals(setOf("鳥"), s.knownKeys())
        assertEquals(KnownLayers(0, 1, 0, 1), s.knownLayers())
        val meta = s.dump().getJSONArray("meta")
        assertFalse((0 until meta.length()).any { meta.getJSONObject(it).getString("key").startsWith("jiten") })
    }

    @Test fun emptyImportClears() {
        s.replaceJiten(KnownImport(5, setOf("犬")), 100, listOf("grammar"))
        s.replaceJiten(KnownImport(6, emptySet()), 0, emptyList())
        assertEquals(JitenState(0, 0, emptyList(), 0), s.jitenImport())
    }

    @Test fun importJitenStoresAndReturnsTheStateOrThrows() {
        val k = JitenKnown({ _, _ -> JitenReply(200, "Word,Form,Rank\n猫,ねこ,1\n犬,いぬ,2\n") }, { 9L })
        assertEquals(JitenState(9, 1, emptyList(), 1), importJiten(s, 1, emptyList(), k))
        val bad = JitenKnown({ _, _ -> throw IOException("offline") }, { 9L })
        assertThrows(IOException::class.java) { importJiten(s, 1, emptyList(), bad) }
        assertEquals(1, s.jitenImport().count) // a failed import keeps the old layer
    }

    @Test fun anImportIsADeltaAppliedToTheHubsNewestListAndSentOnce() {
        val hub = Hub(10, setOf("hub1", "hub2"))
        hub.sync(s)
        assertEquals(null, s.pendingShared())
        s.replaceJiten(imp("a", "b"), 2, emptyList())
        assertEquals(null, s.pendingShared()) // nothing built at import time
        hub.words = setOf("hub1", "hub3"); hub.time = 20 // kumapie changed meanwhile: that is the base
        hub.sync(s)
        val (t, w) = s.pendingShared()!!
        assertTrue(t > 20)
        assertEquals(listOf("a", "b", "hub1", "hub3"), w)
        hub.sync(s) // sent
        assertEquals(setOf("a", "b", "hub1", "hub3"), hub.words)
        assertEquals(null, s.pendingShared())
        hub.sync(s)
        assertEquals(null, s.pendingShared())
    }

    @Test fun twoDevicesImportingFromAStaleBaseBothSurvive() {
        val hub = Hub(10, setOf("k1"))
        val a = store("a"); val b = store("b")
        hub.sync(a); hub.sync(b)
        a.replaceJiten(imp("a1"), 1, emptyList())
        b.replaceJiten(imp("b1"), 1, emptyList()) // from the same stale base {k1}
        hub.sync(a); hub.sync(a) // A's delta applied, then sent
        assertEquals(setOf("k1", "a1"), hub.words)
        hub.sync(b); hub.sync(b) // B takes A's list as the base, adds its own
        assertEquals(setOf("k1", "a1", "b1"), hub.words)
        hub.sync(a)
        assertEquals(setOf("k1", "a1", "b1"), a.sharedWords())
    }

    @Test fun aSlideDownRemovesOnlyWhatThisDeviceAddedAndIsNotMined() {
        val hub = Hub(10, setOf("k1", "x")) // x is kumapie's own (its own Jiten import overlaps ours)
        hub.sync(s)
        s.replaceJiten(imp("x", "a", "b", "c"), 4, emptyList())
        hub.sync(s); hub.sync(s)
        assertEquals(setOf("k1", "x", "a", "b", "c"), hub.words)
        s.addCard("b[b]", "s", "m", null, null, null, JSONObject().put("Reading", "b").toString(), now = 1) // b is mined here
        s.replaceJiten(imp("a"), 1, emptyList()) // slide down: x (not ours), b (mined) stay; c goes
        hub.sync(s); hub.sync(s)
        assertEquals(setOf("k1", "x", "a", "b"), hub.words)
        s.replaceJiten(imp(), 0, emptyList()) // cleared: a goes
        hub.sync(s); hub.sync(s)
        assertEquals(setOf("k1", "x", "b"), hub.words)
        assertEquals(0, s.jitenImport().count)
    }

    private fun carded(device: String, made: Long, vararg keys: String) =
        JSONObject().put(device, JSONObject().put("made", made).put("keys", org.json.JSONArray(keys.toList())))

    @Test fun wordsCardedOnOtherDevicesAreMinedAndSurviveASlideDown() {
        val hub = Hub(10, setOf("k1"))
        hub.sync(s)
        s.replaceJiten(imp("a", "b", "c"), 3, emptyList())
        hub.sync(s); hub.sync(s)
        s.takeCarded(carded("tablet", 5, "ja|b", "ja|c\t" + "とり", "de|a"), "pc")
        assertEquals(listOf("b", "c\t" + "とり"), s.carded().map { it.first })
        s.replaceJiten(imp(), 0, emptyList()) // cleared: a goes, b and c are carded on the tablet
        hub.sync(s); hub.sync(s)
        assertEquals(setOf("k1", "b", "c"), hub.words)
    }

    @Test fun cardedReplacesWhatWasKeptAndSkipsOurOwn() {
        s.takeCarded(carded("tablet", 5, "ja|new").put("pc", JSONObject().put("made", 9).put("keys", org.json.JSONArray(listOf("ja|mine")))), "pc")
        assertEquals(listOf("new"), s.carded().map { it.first })
        s.takeCarded(carded("phone", 6, "ja|newer"), "pc") // the tablet is gone from the answer
        assertEquals(listOf("newer"), s.carded().map { it.first })
        s.forgetShared() // paired with another hub
        assertEquals(emptyList<Pair<String, List<String>>>(), s.carded())
    }

    @Test fun aDeltaWaitsForTheFirstAnswerWithWordsAndAnOlderAnswerDoesNotOverwrite() {
        s.replaceJiten(imp("a", "b"), 2, emptyList()) // never synced / not paired
        assertEquals(null, s.pendingShared())
        assertEquals(emptySet<String>(), s.sharedWords())
        assertTrue(s.isKnownSpelling("a") && s.knownLayers().jiten == 2)
        s.takeShared(0, null) // an answer without known.ja words: not taken, delta kept
        assertEquals(null, s.pendingShared())
        s.takeShared(10, setOf("hub1", "a")) // the first answer with words: nothing of the hub's lost
        assertEquals(setOf("hub1", "a", "b"), s.sharedWords())
        val (t, w) = s.pendingShared()!!
        assertTrue(t > 10 && w == listOf("a", "b", "hub1"))
        s.takeShared(5, setOf("old")) // an older hub list: ours stays pending
        assertEquals(setOf("hub1", "a", "b"), s.sharedWords())
        assertEquals(t to w, s.pendingShared())
        s.takeShared(t, setOf("hub1", "a", "b")) // merged by the hub
        assertEquals(null, s.pendingShared())
    }

    @Test fun anEmptyDeltaSendsNothing() {
        s.replaceJiten(imp(), 0, emptyList()) // clear before the first sync
        s.takeShared(10, setOf("k"))
        assertEquals(null, s.pendingShared())
        s.replaceJiten(imp("a"), 1, emptyList())
        s.replaceJiten(imp(), 0, emptyList()) // slide down before the first apply: nothing was ever sent, nothing to remove
        s.takeShared(20, setOf("k"))
        assertEquals(null, s.pendingShared())
        assertEquals(setOf("k"), s.sharedWords())
    }

    @Test fun pairingAgainForgetsTheHubsListButKeepsTheDelta() {
        s.takeShared(10, setOf("k"))
        s.replaceJiten(imp("a"), 1, emptyList())
        s.takeShared(10, setOf("k"))
        assertTrue(s.pendingShared() != null)
        s.replaceJiten(imp("a", "c"), 2, emptyList())
        s.forgetShared()
        assertEquals(null, s.pendingShared())
        assertEquals(emptySet<String>(), s.sharedWords())
        s.takeShared(3, setOf("other")) // another hub, older time: still taken whole
        assertEquals(setOf("other", "a", "c"), s.sharedWords())
        assertTrue(s.pendingShared() != null)
    }

    @Test fun knownWordsOfUsesTheSharedListInsteadOfTheAnswersOlderOne() {
        val answer = JSONObject("""{"known":{"ja":{"time":1,"words":["old"]}},"events":[]}""")
        assertEquals(setOf("a"), KnownWords.of(answer, Fsrs6(), setOf("a")))
        assertEquals(setOf("old"), KnownWords.of(answer, Fsrs6()))
    }
}
