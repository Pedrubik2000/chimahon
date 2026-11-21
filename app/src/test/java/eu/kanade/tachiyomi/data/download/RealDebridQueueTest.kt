package eu.kanade.tachiyomi.data.download

import androidx.work.WorkInfo
import eu.kanade.tachiyomi.data.download.RealDebridQueue.Companion.CANCELLED
import eu.kanade.tachiyomi.data.download.RealDebridQueue.State
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RealDebridQueueTest {

    private fun state(work: WorkInfo.State, error: String? = null, flagged: Boolean = false) =
        RealDebridQueue.stateOf(work, error, flagged)

    @Test
    fun `an error ends as success and reads as failed, a clean success as done`() {
        state(WorkInfo.State.SUCCEEDED, "Real-Debrid /unrestrict/link: HTTP 503") shouldBe
            (State.FAILED to "Real-Debrid /unrestrict/link: HTTP 503")
        state(WorkInfo.State.SUCCEEDED) shouldBe (State.DONE to null)
        // a cancelled job ends itself as success with the error "Cancelled": the pages hide it
        state(WorkInfo.State.SUCCEEDED, CANCELLED) shouldBe (State.FAILED to "Cancelled")
        // finished before it saw the cancel: it is there, say so
        state(WorkInfo.State.SUCCEEDED, flagged = true) shouldBe (State.DONE to null)
    }

    @Test
    fun `cancelled ids read as cancelled at once, others keep their state`() {
        state(WorkInfo.State.ENQUEUED) shouldBe (State.QUEUED to null)
        state(WorkInfo.State.BLOCKED) shouldBe (State.QUEUED to null)
        state(WorkInfo.State.RUNNING) shouldBe (State.RUNNING to null)
        state(WorkInfo.State.BLOCKED, flagged = true) shouldBe (State.FAILED to CANCELLED)
        state(WorkInfo.State.RUNNING, flagged = true) shouldBe (State.FAILED to CANCELLED)
    }

    @Test
    fun `WorkManager's own cancel and failures of the previous version`() {
        state(WorkInfo.State.CANCELLED) shouldBe (State.FAILED to CANCELLED) // "Cancel all"
        state(WorkInfo.State.FAILED, "No Real-Debrid token") shouldBe (State.FAILED to "No Real-Debrid token")
        state(WorkInfo.State.FAILED) shouldBe (State.FAILED to "An earlier job in the queue failed")
    }

    @Test
    fun `cancelled ids are kept only while their work is unfinished`() {
        RealDebridQueue.pruned(setOf("a", "b", "c"), setOf("b", "x")) shouldBe setOf("b")
        RealDebridQueue.pruned(emptySet(), setOf("x")) shouldBe emptySet()
    }
}
