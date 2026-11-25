package chimahon.releases

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class AutoFetchPlanTest {
    @TempDir lateinit var dir: File

    private val today = LocalDate.parse("2026-11-25")
    private val yesterday = today.minusDays(1)
    private val gb = 1024L * 1024 * 1024

    private fun row(id: Long, vol: Int, state: VolState) = VolumeRow(id, vol, today, state, null, today, today)
    private fun att(id: Long, vol: Int, fails: Int = 0, day: LocalDate = yesterday, pending: Boolean = false, tried: List<Long> = emptyList(), finished: Boolean = false) =
        FetchAttempt(id, vol, tried, fails, day, pending, finished)

    private fun pick(
        cands: List<Pair<Long, Int>>,
        attempts: List<FetchAttempt> = emptyList(),
        unmetered: Boolean = true,
        free: Long = 20 * gb,
        done: Int = 0,
    ) = AutoFetchPlan.pick(cands, attempts, today, unmetered, free, done)

    @Test fun `candidates are the lowest volume on sources above lastKnownVol of reading, watched, auto series`() {
        val series = listOf(
            WatchedSeries(1, "a", lastKnownVol = 5),
            WatchedSeries(2, "b", lastKnownVol = 5, status = WatchStatus.PLAN),
            WatchedSeries(3, "c", lastKnownVol = 5, watching = false),
            WatchedSeries(4, "d", lastKnownVol = 5),
            WatchedSeries(5, "e", lastKnownVol = 5),
        )
        val rows = listOf(6, 7).flatMap { v -> listOf(1L, 2, 3, 4).map { row(it, v, VolState.ON_SOURCES) } } +
            row(5, 6, VolState.RELEASED) + row(1, 5, VolState.ON_SOURCES) // 5 is not above lastKnownVol
        val got = AutoFetchPlan.candidates(series, rows) { it.anilistId != 4L }
        assertEquals(listOf(1L to 6), got)
    }

    @Test fun `nothing off Wi-Fi or without more than 5 GB free or when free is unknown`() {
        val c = listOf(1L to 6)
        assertEquals(c, pick(c))
        assertEquals(emptyList<Pair<Long, Int>>(), pick(c, unmetered = false))
        assertEquals(emptyList<Pair<Long, Int>>(), pick(c, free = 5 * gb))
        assertEquals(c, pick(c, free = 5 * gb + 1))
        assertEquals(emptyList<Pair<Long, Int>>(), pick(c, free = -1))
    }

    @Test fun `at most 3 a day counting what was queued already today`() {
        val c = (1L..5L).map { it to 2 }
        assertEquals(c.take(3), pick(c))
        assertEquals(c.take(1), pick(c, done = 2))
        assertEquals(emptyList<Pair<Long, Int>>(), pick(c, done = 3))
        assertEquals(emptyList<Pair<Long, Int>>(), pick(c, done = 9))
    }

    @Test fun `the day's count resets on a new JST day`() {
        val s = ReleaseState(fetchDay = yesterday, fetchCount = 3)
        assertEquals(0, AutoFetchPlan.fetchedToday(s, today))
        assertEquals(3, AutoFetchPlan.fetchedToday(s, yesterday))
        assertEquals(today to 1, AutoFetchPlan.counted(s, today, 1))
        assertEquals(yesterday to 4, AutoFetchPlan.counted(s, yesterday, 1))
    }

    @Test fun `a failed try waits for the next day and 3 fails stop it, pending ones are left alone`() {
        val c = listOf(1L to 6, 2L to 6, 3L to 6, 4L to 6, 5L to 6)
        val attempts = listOf(
            att(1, 6, fails = 1, day = today), // failed today: tomorrow
            att(2, 6, fails = 1, day = yesterday), // failed yesterday: again
            att(3, 6, fails = 3, day = yesterday), // given up
            att(4, 6, pending = true, day = today), // running
        )
        assertEquals(listOf(2L to 6, 5L to 6), pick(c, attempts))
    }

    @Test fun `queued marks the attempt pending today and keeps the sources already tried`() {
        val a = AutoFetchPlan.queued(listOf(att(1, 6, fails = 1, tried = listOf(10L))), 1, 6, 11L, today)
        assertEquals(listOf(FetchAttempt(1, 6, listOf(10L, 11L), 1, today, pending = true)), a)
        assertEquals(listOf(FetchAttempt(2, 3, emptyList(), 0, today, true)), AutoFetchPlan.queued(emptyList(), 2, 3, null, today))
    }

    @Test fun `an ended job drops a delivered attempt, counts a failure otherwise, keeps a cancelled one`() {
        val start = listOf(att(1, 6, pending = true, day = today), att(1, 7, pending = true, day = today), att(2, 6, pending = true, day = today), att(1, 9, fails = 1))
        val ok = AutoFetchPlan.ended(start, 1, listOf(6, 7), listOf(6), cancelled = false, sources = listOf(10L, 11L))
        assertEquals(listOf(1L to 6), ok.delivered)
        assertEquals(
            listOf(att(1, 6, day = today, finished = true), att(1, 7, fails = 1, day = today, tried = listOf(10L, 11L)), att(2, 6, pending = true, day = today), att(1, 9, fails = 1)),
            ok.attempts,
        )
        val cancelled = AutoFetchPlan.ended(start, 2, listOf(6), emptyList(), cancelled = true, sources = emptyList())
        assertEquals(att(2, 6, day = today, finished = true), cancelled.attempts.first { it.anilistId == 2L })
        assertTrue(cancelled.delivered.isEmpty())
        // not an auto-fetch (no pending attempt for it): nothing changes
        assertEquals(start, AutoFetchPlan.ended(start, 3, listOf(1), listOf(1), false, emptyList()).attempts)
    }

    @Test fun `a pending attempt older than today failed, three failures give up`() {
        val e = AutoFetchPlan.expire(listOf(att(1, 6, pending = true, day = yesterday), att(2, 6, pending = true, day = today)), today)
        assertEquals(listOf(att(1, 6, fails = 1), att(2, 6, pending = true, day = today)), e)
        assertEquals(emptyList<FetchAttempt>(), AutoFetchPlan.gaveUp(e, live))
        assertEquals(listOf(att(1, 6, fails = 3)), AutoFetchPlan.gaveUp(listOf(att(1, 6, fails = 3), att(2, 6, fails = 2)), live))
    }

    @Test fun `attempts of volumes no longer waiting on sources are forgotten`() {
        val rows = listOf(row(1, 6, VolState.ON_SOURCES), row(1, 7, VolState.ON_TABLET), row(2, 3, VolState.RELEASED))
        val a = listOf(att(1, 6), att(1, 7), att(2, 3), att(9, 1))
        assertEquals(listOf(att(1, 6), att(2, 3)), AutoFetchPlan.prune(a, rows))
    }

    @Test fun `the store keeps watching, attempts and the day's count, old files load without them`() {
        val s = ReleaseState(
            series = listOf(WatchedSeries(1, "a", watching = false), WatchedSeries(2, "b", autoFetch = false, watching = true), WatchedSeries(3, "c")),
            attempts = listOf(att(1, 6, fails = 2, day = today, pending = true, tried = listOf(10L, 11L))),
            fetchDay = today, fetchCount = 2,
        )
        val f = File(dir, "p5.json")
        ReleaseStore(f).update { s }
        assertEquals(s, ReleaseStore(f).get())
        val old = File(dir, "old.json")
        old.writeText(
            ReleaseStore.toJson(s).also {
                it.remove("attempts"); it.remove("fetchDay"); it.remove("fetchCount")
                for (i in 0 until it.getJSONArray("series").length()) it.getJSONArray("series").getJSONObject(i).remove("watching")
            }.toString(),
        )
        val loaded = ReleaseStore(old).get()
        assertEquals(s.copy(attempts = emptyList(), fetchDay = null, fetchCount = 0, series = s.series.map { it.copy(watching = null) }), loaded)
    }

    private val live = listOf(WatchedSeries(1, "a"), WatchedSeries(2, "b"))

    @Test fun `expire leaves a job that is still queued alone and finishes a cancelled one without a failure`() {
        val p = listOf(att(1, 6, pending = true), att(2, 6, pending = true), att(3, 6, pending = true))
        val e = AutoFetchPlan.expire(p, today, active = setOf(1L to 6), cancelled = setOf(2L to 6))
        assertEquals(att(1, 6, pending = true), e[0]) // waiting in the serial chain
        assertEquals(att(2, 6, finished = true), e[1]) // "Cancel all": no failure, no retry
        assertEquals(att(3, 6, fails = 1), e[2])
    }

    @Test fun `a delivered or cancelled attempt is finished, kept until the row moves on, never picked, never a problem`() {
        val kept = AutoFetchPlan.ended(listOf(att(1, 6, pending = true, day = today)), 1, listOf(6), listOf(6), false, emptyList()).attempts
        assertEquals(listOf(att(1, 6, day = today, finished = true)), kept)
        // still ON_SOURCES in the store (the engine has not seen the tablet yet): the marker stays, no second download tomorrow
        assertEquals(kept, AutoFetchPlan.prune(kept, listOf(row(1, 6, VolState.ON_SOURCES))))
        assertEquals(emptyList<Pair<Long, Int>>(), AutoFetchPlan.pick(listOf(1L to 6), kept.map { it.copy(lastDay = yesterday) }, today, true, 20 * gb, 0))
        // the row reaches ON_TABLET: forgotten
        assertEquals(emptyList<FetchAttempt>(), AutoFetchPlan.prune(kept, listOf(row(1, 6, VolState.ON_TABLET))))
        assertEquals(emptyList<FetchAttempt>(), AutoFetchPlan.gaveUp(listOf(att(1, 6, fails = 3, finished = true)), live))
    }

    @Test fun `a cancelled job is not retried the next day`() {
        val c = AutoFetchPlan.ended(listOf(att(1, 6, pending = true, day = yesterday)), 1, listOf(6), emptyList(), true, emptyList()).attempts
        assertEquals(emptyList<Pair<Long, Int>>(), AutoFetchPlan.pick(listOf(1L to 6), c, today, true, 20 * gb, 0))
    }

    @Test fun `given up shows only for watched reading series`() {
        val a = listOf(att(1, 6, fails = 3), att(2, 6, fails = 3), att(3, 6, fails = 3), att(4, 6, fails = 3))
        val series = listOf(
            WatchedSeries(1, "a"), WatchedSeries(2, "b", watching = false), WatchedSeries(3, "c", status = WatchStatus.PLAN),
        ) // 4: not in the list any more
        assertEquals(listOf(att(1, 6, fails = 3)), AutoFetchPlan.gaveUp(a, series))
    }

    @Test fun `finished survives the file, old attempts load as not finished`() {
        val s = ReleaseState(attempts = listOf(att(1, 6, day = today, finished = true)))
        val f = File(dir, "fin.json")
        ReleaseStore(f).update { s }
        assertEquals(s, ReleaseStore(f).get())
        val old = File(dir, "fin-old.json")
        old.writeText(ReleaseStore.toJson(s).also { it.getJSONArray("attempts").getJSONObject(0).remove("finished") }.toString())
        assertEquals(att(1, 6, day = today), ReleaseStore(old).get().attempts.single())
    }

    @Test fun `only the very next volume is a candidate, never when behind or at an unknown place, even with auto-fetch forced on`() {
        val series = listOf(
            WatchedSeries(1, "a", lastKnownVol = 10, autoFetch = true), // next 11 on sources
            WatchedSeries(2, "b", lastKnownVol = 10, autoFetch = true), // only 30 on sources: behind
            WatchedSeries(3, "c", lastKnownVol = 0, autoFetch = true), // unknown place
        )
        val rows = listOf(row(1, 11, VolState.ON_SOURCES), row(2, 30, VolState.ON_SOURCES), row(3, 1, VolState.ON_SOURCES))
        assertEquals(listOf(1L to 11), AutoFetchPlan.candidates(series, rows) { true })
    }
}
