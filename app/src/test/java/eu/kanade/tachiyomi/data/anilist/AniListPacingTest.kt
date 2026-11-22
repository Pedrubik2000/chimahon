package eu.kanade.tachiyomi.data.anilist

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class AniListPacingTest {

    @Test
    fun `adopting 21 folders stays under AniList's 30 a minute`() {
        // a bind is 2-3 requests (find the list entry, maybe its volumes again, then save): the limiter paces them
        (AniListPacing.PER_MINUTE < 30) shouldBe true
    }

    @Test
    fun `retry-after is seconds, anything else waits a minute`() {
        AniListPacing.retryAfterMillis("7") shouldBe 7_000L
        AniListPacing.retryAfterMillis(" 60 ") shouldBe 60_000L
        AniListPacing.retryAfterMillis("0") shouldBe 1_000L
        AniListPacing.retryAfterMillis("99999") shouldBe 300_000L
        AniListPacing.retryAfterMillis(null) shouldBe AniListPacing.DEFAULT_WAIT
        AniListPacing.retryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT") shouldBe AniListPacing.DEFAULT_WAIT
    }

    @Test
    fun `a pass waits out a 429 first, else runs at once and retries a minute apart`() {
        AniListPacing.waitBeforePass(0, blockedUntil = 0L, now = 1_000L) shouldBe 0L
        AniListPacing.waitBeforePass(0, blockedUntil = 8_000L, now = 1_000L) shouldBe 7_000L
        AniListPacing.waitBeforePass(1, blockedUntil = 8_000L, now = 1_000L) shouldBe 7_000L
        AniListPacing.waitBeforePass(1, blockedUntil = 0L, now = 1_000L) shouldBe AniListPacing.DEFAULT_WAIT
    }

    @Test
    fun `a second pass while one runs is dropped`() = runTest {
        val running = AtomicBoolean(false)
        val gate = CompletableDeferred<Unit>()
        val first = async { AniListPacing.onePass(running) { gate.await(); 1 } }
        testScheduler.runCurrent()
        AniListPacing.onePass(running) { 2 } shouldBe null
        gate.complete(Unit)
        first.await() shouldBe 1
        AniListPacing.onePass(running) { 3 } shouldBe 3
    }
}
