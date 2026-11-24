package chimahon.anki

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DropRubyTest {
    @Test
    fun keepsTheBaseOnly() {
        assertEquals(
            "自分が約束",
            dropRuby("""<ruby class="gloss-sc-ruby">自<rt class="gloss-sc-rt">じ</rt></ruby><ruby>分<rp>(</rp><rt>ぶん</rt><rp>)</rp></ruby>が<ruby>約束<rt>やくそく</rt></ruby>"""),
        )
        assertEquals("<span>no ruby</span>", dropRuby("<span>no ruby</span>"))
    }
}
