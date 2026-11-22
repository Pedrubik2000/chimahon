package eu.kanade.tachiyomi.data.dictionary

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ResumedProgressTest {

    @Test
    fun `bytes grow with the listener and carry over a resumed attempt`() {
        var onDisk = 0L
        val p = ResumedProgress { onDisk }
        p.bytes(8_192) shouldBe 8_192L
        onDisk = 8_192 // written after the callback
        p.bytes(1_000_000) shouldBe 1_000_000L
        onDisk = 1_000_000
        // the connection drops; the next attempt asks for the rest and its count starts again
        p.bytes(8_192) shouldBe 1_008_192L
        onDisk = 1_008_192
        p.bytes(500_000) shouldBe 1_500_000L
    }

    @Test
    fun `a server that ignored the range starts over from an empty file`() {
        var onDisk = 0L
        val p = ResumedProgress { onDisk }
        p.bytes(2_000_000) shouldBe 2_000_000L
        onDisk = 0 // the part was deleted before the new attempt's first read
        p.bytes(8_192) shouldBe 8_192L
    }
}
