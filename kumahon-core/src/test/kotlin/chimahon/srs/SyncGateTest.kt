package chimahon.srs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SyncGateTest {
    @Test
    fun `a burst is one run`() {
        val g = SyncGate()
        g.request() shouldBe true // arms the timer
        g.request() shouldBe false
        g.request() shouldBe false // coalesced
        g.start() shouldBe true
        g.start() shouldBe false // never two in flight
        g.done() shouldBe false
        g.request() shouldBe true // idle again
    }

    @Test
    fun `a request during a run asks for one more after it`() {
        val g = SyncGate()
        g.request(); g.start()
        g.request() shouldBe false
        g.request() shouldBe false
        g.done() shouldBe true // one more, once
        g.start() shouldBe true
        g.done() shouldBe false
    }

    @Test
    fun `start without a timer does nothing`() {
        SyncGate().start() shouldBe false
    }

    @Test
    fun `on start it syncs after 15 minutes and never before`() {
        val min = 60_000L
        SyncGate.dueOnStart(0, 1_000_000) shouldBe true
        SyncGate.dueOnStart(1_000_000, 1_000_000 + 14 * min) shouldBe false
        SyncGate.dueOnStart(1_000_000, 1_000_000 + 16 * min) shouldBe true
    }
}
