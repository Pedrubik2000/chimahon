package chimahon.releases

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ReleaseEngineTest {
    private val engine = ReleaseEngine()
    private fun d(s: String) = LocalDate.parse(s)

    /** A JST time on the given day (the engine reads the day in Tokyo). */
    private fun at(day: String, hour: Int = 9) = d(day).atTime(hour, 0).atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant()
    private fun l(title: String, date: String?, source: String = "books.or.jp", publisher: String = "集英社") = Listing(title, null, date?.let(::d), publisher, false, source)

    private val dandadan = WatchedSeries(1, "ダンダダン", "集英社", lastKnownVol = 25)
    private val start = ReleaseState(series = listOf(dandadan))

    private fun row(s: ReleaseState, vol: Int, id: Long = 1) = s.rows.first { it.anilistId == id && it.vol == vol }

    @Test fun announcedThenOutToday() {
        val listings = mapOf(1L to listOf(l("ダンダダン 26", "2026-11-04")))
        val a = engine.run(at("2026-10-20"), start, listings)
        assertEquals(VolState.ANNOUNCED, row(a.state, 26).state)
        assertEquals(d("2026-11-04"), row(a.state, 26).date)
        assertEquals("books.or.jp", row(a.state, 26).source)
        assertEquals(d("2026-10-20"), row(a.state, 26).firstSeen)
        assertTrue(a.events.isEmpty())

        val b = engine.run(at("2026-11-04"), a.state, listings)
        assertEquals(VolState.RELEASED, row(b.state, 26).state)
        assertEquals(listOf(ReleaseEvent(EventKind.OUT_TODAY, 1, 26)), b.events)
        assertEquals(d("2026-10-20"), row(b.state, 26).firstSeen)
        assertEquals(d("2026-11-04"), row(b.state, 26).lastChecked)
    }

    @Test fun releasedUsesTokyoDay() {
        val listings = mapOf(1L to listOf(l("ダンダダン 26", "2026-11-04")))
        // 2026-11-03 23:30 in Tokyo is still before the day, whatever UTC says
        val before = d("2026-11-03").atTime(23, 30).atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant()
        assertTrue(engine.run(before, start, listings).events.isEmpty())
        // 2026-11-03 15:30 UTC = 2026-11-04 00:30 JST
        val after = Instant.parse("2026-11-03T15:30:00Z")
        assertEquals(EventKind.OUT_TODAY, engine.run(after, start, listings).events.single().kind)
    }

    @Test fun noDuplicateEvents() {
        val listings = mapOf(1L to listOf(l("ダンダダン 26", "2026-11-04")))
        val b = engine.run(at("2026-11-04"), start, listings)
        val c = engine.run(at("2026-11-05"), b.state, listings)
        assertTrue(c.events.isEmpty())
        // even from a state whose row was lost, the notified keys hold
        val lost = engine.run(at("2026-11-06"), b.state.copy(rows = emptyList()), listings)
        assertTrue(lost.events.isEmpty())
    }

    @Test fun onSourcesThenTabletThenRead() {
        val listings = mapOf(1L to listOf(l("ダンダダン 26", "2026-11-04")))
        val b = engine.run(at("2026-11-04"), start, listings)
        val cov = mapOf(1L to mapOf(26 to "X3DL"))
        val c = engine.run(at("2026-11-05"), b.state, listings, coverage = cov)
        assertEquals(VolState.ON_SOURCES, row(c.state, 26).state)
        assertEquals(listOf(ReleaseEvent(EventKind.ON_SOURCES, 1, 26, source = "X3DL")), c.events)
        val t = engine.run(at("2026-11-06"), c.state, listings, coverage = cov, local = mapOf(1L to setOf(26)))
        assertEquals(VolState.ON_TABLET, row(t.state, 26).state)
        assertTrue(t.events.isEmpty())
        val r = engine.run(at("2026-11-07"), t.state, listings, coverage = cov, local = mapOf(1L to setOf(26)), read = mapOf(1L to setOf(26)))
        assertEquals(VolState.READ, row(r.state, 26).state)
        // never back: the source forgets the volume and the tablet loses it, the row stays READ
        assertEquals(VolState.READ, row(engine.run(at("2026-11-08"), r.state, listings).state, 26).state)
    }

    @Test fun releasedAndOnSourcesInOneRunGivesBoth() {
        val listings = mapOf(1L to listOf(l("ダンダダン 26", "2026-11-04")))
        val r = engine.run(at("2026-11-05"), start, listings, coverage = mapOf(1L to mapOf(26 to "Excnn")))
        assertEquals(setOf(EventKind.OUT_TODAY, EventKind.ON_SOURCES), r.events.map { it.kind }.toSet())
    }

    @Test fun oldReleaseIsNotNews() {
        val listings = mapOf(1L to listOf(l("ダンダダン 26", "2026-09-04")))
        val r = engine.run(at("2026-11-05"), start, listings)
        assertEquals(VolState.RELEASED, row(r.state, 26).state)
        assertTrue(r.events.isEmpty())
    }

    @Test fun volumeOnSourcesWithoutADate() {
        val r = engine.run(at("2026-11-05"), start, coverage = mapOf(1L to mapOf(26 to "Rawxt", 25 to "Rawxt")))
        assertEquals(null, row(r.state, 26).date)
        assertEquals(VolState.ON_SOURCES, row(r.state, 26).state)
        assertEquals(listOf(ReleaseEvent(EventKind.ON_SOURCES, 1, 26, source = "Rawxt")), r.events) // 25 is not newer than lastKnownVol
        // the date arrives later: it is filled in, no DATE_CHANGED
        val late = engine.run(at("2026-11-06"), r.state, mapOf(1L to listOf(l("ダンダダン 26", "2026-11-04"))), coverage = mapOf(1L to mapOf(26 to "Rawxt")))
        assertEquals(d("2026-11-04"), row(late.state, 26).date)
        assertTrue(late.events.isEmpty())
    }

    @Test fun dateChanged() {
        val first = engine.run(at("2026-10-20"), start, mapOf(1L to listOf(l("ダンダダン 26", "2026-11-04"))))
        val delayed = engine.run(at("2026-10-30"), first.state, mapOf(1L to listOf(l("ダンダダン 26", "2026-11-18"))))
        assertEquals(listOf(ReleaseEvent(EventKind.DATE_CHANGED, 1, 26, oldDate = d("2026-11-04"), newDate = d("2026-11-18"))), delayed.events)
        assertEquals(d("2026-11-18"), row(delayed.state, 26).date)
        assertEquals(d("2026-11-04"), row(delayed.state, 26).previousDate)
        // the same news again: nothing
        assertTrue(engine.run(at("2026-10-31"), delayed.state, mapOf(1L to listOf(l("ダンダダン 26", "2026-11-18")))).events.isEmpty())
        // it is not out on the old date any more
        val old = engine.run(at("2026-11-04"), delayed.state, mapOf(1L to listOf(l("ダンダダン 26", "2026-11-18"))))
        assertEquals(VolState.ANNOUNCED, row(old.state, 26).state)
        assertTrue(old.events.isEmpty())
        // a second, different change is news again
        val again = engine.run(at("2026-11-10"), delayed.state, mapOf(1L to listOf(l("ダンダダン 26", "2026-12-02"))))
        assertEquals(EventKind.DATE_CHANGED, again.events.single().kind)
    }

    @Test fun dateMovedOnReleaseDayBeforeItCounts() {
        val first = engine.run(at("2026-11-01"), start, mapOf(1L to listOf(l("ダンダダン 26", "2026-11-04"))))
        val r = engine.run(at("2026-11-04"), first.state, mapOf(1L to listOf(l("ダンダダン 26", "2026-11-11"))))
        assertEquals(listOf(EventKind.DATE_CHANGED), r.events.map { it.kind })
        assertEquals(VolState.ANNOUNCED, row(r.state, 26).state)
    }

    @Test fun releasedRowKeepsItsDate() {
        val b = engine.run(at("2026-11-04"), start, mapOf(1L to listOf(l("ダンダダン 26", "2026-11-04"))))
        val later = engine.run(at("2026-11-06"), b.state, mapOf(1L to listOf(l("ダンダダン 26", "2026-11-30"))))
        assertEquals(d("2026-11-04"), row(later.state, 26).date)
        assertTrue(later.events.isEmpty())
    }

    @Test fun earliestSourceWinsAndAMissingSourceDoesNotFlap() {
        val kodansha = WatchedSeries(2, "ブルーロック", "講談社", lastKnownVol = 41)
        val s0 = ReleaseState(series = listOf(kodansha))
        val jpro = l("ブルーロック 42", "2027-01-16", "books.or.jp", "講談社")
        val cal = l("ブルーロック（４２）", "2027-01-15", "kodansha", "講談社")
        val a = engine.run(at("2026-11-25"), s0, mapOf(2L to listOf(jpro)))
        assertEquals(d("2027-01-16"), row(a.state, 42, 2).date)
        // the stored source (books.or.jp) is silent this run and the calendar knows it earlier: the earlier date wins, and it is news
        val b = engine.run(at("2026-11-26"), a.state, mapOf(2L to listOf(cal)))
        assertEquals(d("2027-01-15"), row(b.state, 42, 2).date)
        assertEquals("kodansha", row(b.state, 42, 2).source)
        assertEquals(1, b.events.count { it.kind == EventKind.DATE_CHANGED })
        // the calendar is down for a run: its date stays, nothing changes
        val c = engine.run(at("2026-11-27"), b.state, mapOf(2L to listOf(jpro)))
        assertEquals(d("2027-01-15"), row(c.state, 42, 2).date)
        assertTrue(c.events.isEmpty())
        // the calendar itself moves to a later day: Kodansha's calendar rules, so the delay wins over books' 01-16, one change
        val moved = engine.run(at("2026-11-28"), c.state, mapOf(2L to listOf(jpro, cal.copy(date = d("2027-01-22")))))
        assertEquals(d("2027-01-22"), row(moved.state, 42, 2).date)
        assertEquals("kodansha", row(moved.state, 42, 2).source)
        assertEquals(1, moved.events.count { it.kind == EventKind.DATE_CHANGED })
    }

    @Test fun kodanshaCalendarDelayWinsOverBooks() {
        val blueLock = WatchedSeries(2, "ブルーロック", "講談社", lastKnownVol = 41)
        val jpro = l("ブルーロック 42", "2026-12-01", "books.or.jp", "講談社")
        val cal = l("ブルーロック（４２）", "2026-12-01", "kodansha", "講談社")
        val a = engine.run(at("2026-11-20"), ReleaseState(series = listOf(blueLock)), mapOf(2L to listOf(jpro, cal)))
        // Kodansha delays to 12-15, books still says 12-01
        val b = engine.run(at("2026-11-22"), a.state, mapOf(2L to listOf(jpro, cal.copy(date = d("2026-12-15")))))
        assertEquals(d("2026-12-15"), row(b.state, 42, 2).date)
        assertEquals(1, b.events.count { it.kind == EventKind.DATE_CHANGED })
        val c = engine.run(at("2026-12-01"), b.state, mapOf(2L to listOf(jpro, cal.copy(date = d("2026-12-15")))))
        assertTrue(c.events.none { it.kind == EventKind.OUT_TODAY })
        assertEquals(d("2026-12-15"), row(c.state, 42, 2).date)
    }

    @Test fun lateEarlierSourceWinsWhileTheFirstStillAnswers() {
        val kodansha = WatchedSeries(2, "ブルーロック", "講談社", lastKnownVol = 41)
        val jpro = l("ブルーロック 42", "2027-01-16", "books.or.jp", "講談社")
        val cal = l("ブルーロック（４２）", "2027-01-15", "kodansha", "講談社")
        val a = engine.run(at("2026-11-25"), ReleaseState(series = listOf(kodansha)), mapOf(2L to listOf(jpro)))
        val b = engine.run(at("2026-11-26"), a.state, mapOf(2L to listOf(jpro, cal)))
        assertEquals(d("2027-01-15"), row(b.state, 42, 2).date)
        assertEquals("kodansha", row(b.state, 42, 2).source)
        assertEquals(1, b.events.count { it.kind == EventKind.DATE_CHANGED })
    }

    @Test fun aVolumeThatLeavesTheCalendarDropsItsStaleDate() {
        val blueLock = WatchedSeries(2, "ブルーロック", "講談社", lastKnownVol = 41)
        val jpro = l("ブルーロック 42", "2026-12-01", "books.or.jp", "講談社")
        val cal = l("ブルーロック（４２）", "2026-12-01", "kodansha", "講談社")
        val calOther = l("ブルーロック（４１）", "2026-10-01", "kodansha", "講談社") // kodansha still answers for the series
        val a = engine.run(at("2026-11-20"), ReleaseState(series = listOf(blueLock)), mapOf(2L to listOf(jpro, cal)))
        // postponed: the calendar drops 42, books moves it
        val b = engine.run(at("2026-11-22"), a.state, mapOf(2L to listOf(jpro.copy(date = d("2026-12-15")), calOther)))
        assertEquals(d("2026-12-15"), row(b.state, 42, 2).date)
        assertEquals(listOf(EventKind.DATE_CHANGED), b.events.map { it.kind })
        // no "Out today" on the old day
        val c = engine.run(at("2026-12-01"), b.state, mapOf(2L to listOf(jpro.copy(date = d("2026-12-15")), calOther)))
        assertTrue(c.events.none { it.kind == EventKind.OUT_TODAY })
    }

    @Test fun ndlToBooksHandoverIsNotADateChange() {
        val a = engine.run(at("2026-10-20"), start, mapOf(1L to listOf(l("ダンダダン 26", "2026-12-03", "ndl"))))
        val b = engine.run(at("2026-10-21"), a.state, mapOf(1L to listOf(l("ダンダダン 26", "2026-12-01", "books.or.jp"))))
        assertEquals(d("2026-12-01"), row(b.state, 26).date)
        assertTrue(b.events.none { it.kind == EventKind.DATE_CHANGED })
    }

    @Test fun aFailedSourceChangesNothingAcrossFourRuns() {
        val jpro = l("ダンダダン 26", "2026-11-04", "books.or.jp")
        val ndl = l("ダンダダン 26", "2026-11-04", "ndl")
        val r1 = engine.run(at("2026-10-20"), start, mapOf(1L to listOf(jpro, ndl)))
        assertEquals(d("2026-11-04"), row(r1.state, 26).date)
        val booksLate = jpro.copy(date = d("2026-11-18"))
        val ndlLate = ndl.copy(date = d("2026-11-18"))
        // both move: a delay, news once
        val r2 = engine.run(at("2026-10-21"), r1.state, mapOf(1L to listOf(booksLate, ndlLate)))
        assertEquals(d("2026-11-18"), row(r2.state, 26).date)
        assertEquals(listOf(EventKind.DATE_CHANGED), r2.events.map { it.kind })
        // books fails (no listing): the other source's date changes nothing
        val r3 = engine.run(at("2026-10-22"), r2.state, mapOf(1L to listOf(ndlLate)))
        assertEquals(d("2026-11-18"), row(r3.state, 26).date)
        assertTrue(r3.events.isEmpty())
        // books is back with the same date: still nothing, and on the old day nothing is out
        val r4 = engine.run(at("2026-11-04"), r3.state, mapOf(1L to listOf(booksLate, ndlLate)))
        assertEquals(d("2026-11-18"), row(r4.state, 26).date)
        assertEquals(VolState.ANNOUNCED, row(r4.state, 26).state)
        assertTrue(r4.events.isEmpty())
    }

    @Test fun onSourcesVolumeThatWentSilentIsAnnouncedWhenItBecomesNext() {
        val cov = mapOf(1L to mapOf(26 to "Excnn", 27 to "Excnn"))
        val r1 = engine.run(at("2026-11-05"), start, coverage = cov)
        assertEquals(listOf(26), r1.events.map { it.vol }) // 27 is on sources but not the next one
        // 26 is read, the series moves on
        val s2 = r1.state.copy(series = listOf(dandadan.copy(lastKnownVol = 26)))
        val r2 = engine.run(at("2026-11-12"), s2, coverage = cov, read = mapOf(1L to setOf(26)))
        assertEquals(listOf(ReleaseEvent(EventKind.ON_SOURCES, 1, 27, source = "Excnn")), r2.events)
        // and only once
        assertTrue(engine.run(at("2026-11-13"), r2.state, coverage = cov, read = mapOf(1L to setOf(26))).events.isEmpty())
    }

    @Test fun staleNdlDateDoesNotHideABooksDelay() {
        val jpro = l("ダンダダン 26", "2026-11-04", "books.or.jp")
        val ndl = l("ダンダダン 26", "2026-11-04", "ndl")
        val a = engine.run(at("2026-10-20"), start, mapOf(1L to listOf(jpro, ndl)))
        val late = jpro.copy(date = d("2026-11-18"))
        val b = engine.run(at("2026-10-30"), a.state, mapOf(1L to listOf(late, ndl)))
        assertEquals(d("2026-11-18"), row(b.state, 26).date)
        assertEquals(listOf(EventKind.DATE_CHANGED), b.events.map { it.kind })
        // on the stale day nothing is out
        val c = engine.run(at("2026-11-04"), b.state, mapOf(1L to listOf(late, ndl)))
        assertEquals(VolState.ANNOUNCED, row(c.state, 26).state)
        assertTrue(c.events.isEmpty())
    }

    @Test fun ndlAloneStillGivesADate() {
        val r = engine.run(at("2026-10-20"), start, mapOf(1L to listOf(l("ダンダダン 26", "2026-11-04", "ndl"))))
        assertEquals(d("2026-11-04"), row(r.state, 26).date)
    }

    @Test fun delayWhenEverySourceMovesIsEditedInPlace() {
        val jpro = l("ダンダダン 26", "2026-11-04", "books.or.jp")
        val ndl = l("ダンダダン 26", "2026-11-04", "ndl")
        val a = engine.run(at("2026-10-20"), start, mapOf(1L to listOf(jpro, ndl)))
        val moved = listOf(jpro.copy(date = d("2026-11-18")), ndl.copy(date = d("2026-11-18")))
        val b = engine.run(at("2026-10-30"), a.state, mapOf(1L to moved))
        assertEquals(d("2026-11-18"), row(b.state, 26).date)
        assertEquals(listOf(EventKind.DATE_CHANGED), b.events.map { it.kind })
        // on the old day the volume is not out
        val c = engine.run(at("2026-11-04"), b.state, mapOf(1L to moved))
        assertEquals(VolState.ANNOUNCED, row(c.state, 26).state)
        assertTrue(c.events.isEmpty())
    }

    @Test fun firstScanAnnouncesOnlyTheNextVolumeOnSources() {
        val cov = mapOf(1L to mapOf(26 to "Excnn", 27 to "Excnn", 28 to "Excnn"))
        val r = engine.run(at("2026-11-05"), start, coverage = cov)
        assertEquals(listOf(ReleaseEvent(EventKind.ON_SOURCES, 1, 26, source = "Excnn")), r.events)
        assertEquals(VolState.ON_SOURCES, row(r.state, 28).state) // the rows are still tracked
    }

    @Test fun planToReadGetsNoOnSourcesEvent() {
        val plan = ReleaseState(series = listOf(dandadan.copy(status = WatchStatus.PLAN)))
        val r = engine.run(at("2026-11-05"), plan, coverage = mapOf(1L to mapOf(26 to "Excnn")))
        assertTrue(r.events.isEmpty())
    }

    @Test fun otherSeriesAndUnwatchedAreLeftAlone() {
        val r = engine.run(at("2026-11-05"), start, mapOf(9L to listOf(l("ダンダダン 26", "2026-11-04"))), coverage = mapOf(9L to mapOf(3 to "X3DL")))
        assertTrue(r.rows().isEmpty())
        assertFalse(r.events.isNotEmpty())
    }

    private fun ReleaseEngine.Result.rows() = state.rows
}
