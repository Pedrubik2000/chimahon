package chimahon.srs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The testable parts of the sync with kumapie: signatures, pairing code, which incoming events are taken. Pure, no Android. */
class PeerSyncTest {
    @Test fun signatureIsHmacSha256Hex() {
        // RFC 4231 test case 2
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", PeerSync.sign("Jefe", "what do ya want for nothing?".toByteArray()))
        val body = """{"events":[]}""".toByteArray()
        val sig = PeerSync.sign("ab".repeat(32), body)
        assertTrue(PeerSync.verify("ab".repeat(32), body, sig))
        assertFalse(PeerSync.verify("ab".repeat(32), body + 1.toByte(), sig))
        assertFalse(PeerSync.verify("cd".repeat(32), body, sig))
        assertFalse(PeerSync.verify("ab".repeat(32), body, null))
    }

    @Test fun codeIsFourDigits() {
        val secret = "ab".repeat(32)
        assertTrue(Regex("[0-9]{4}").matches(PeerSync.code(secret)))
        assertEquals(PeerSync.code(secret), PeerSync.code(secret))
        assertNotEquals(PeerSync.code(secret), PeerSync.code("50".repeat(32)))
    }

    @Test fun eventsAreMatchedToTheLowestCardOfTheKeyAndTakenOnce() {
        val cards = PeerSync.byKey(listOf(30L to "ja|魔法\tまほう", 10L to "ja|魔法\tまほう", 20L to "ja|茶\tちゃ", 40L to null))
        assertEquals(mapOf("ja|魔法\tまほう" to 10L, "ja|茶\tちゃ" to 20L), cards)
        val incoming = listOf(1L to "ja|魔法\tまほう", 2L to "ja|茶\tちゃ", 3L to "ja|犬\tいぬ", 4L to "de|hund", 5L to "ja|魔法\tまほう", -1L to "ja|茶\tちゃ")
        val take = PeerSync.pick(incoming, { it.first }, { it.second }, setOf(2L), cards)
        assertEquals(listOf(1L to 10L, 5L to 10L), take.map { it.first.first to it.second })
        // the same answer again once those rows are in the revlog: nothing to take
        assertTrue(PeerSync.pick(incoming, { it.first }, { it.second }, setOf(1L, 2L, 5L), cards).isEmpty())
    }
}
