package eu.kanade.presentation.reader.glass

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlassLiveStatsTest {

    @Test
    fun `speed is cut to one decimal of thousands`() {
        formatSpeed(26_576) shouldBe "26.5k"
        formatSpeed(1_000) shouldBe "1.0k"
        formatSpeed(950) shouldBe "950"
        formatSpeed(0) shouldBe "0"
    }

    @Test
    fun `clock`() {
        formatClock(31_000) shouldBe "0:31"
        formatClock(725_000) shouldBe "12:05"
        formatClock(3_729_000) shouldBe "1:02:09"
    }
}
