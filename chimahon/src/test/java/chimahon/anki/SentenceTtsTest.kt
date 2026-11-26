package chimahon.anki

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class SentenceTtsTest {
    @Test
    fun detectsJapanese() {
        assertTrue(SentenceTts.hasJapanese("猫がいる"))
        assertTrue(SentenceTts.hasJapanese("カタカナ"))
        assertFalse(SentenceTts.hasJapanese("hello 123"))
    }
}
