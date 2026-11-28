package eu.kanade.tachiyomi.ui.glass.stats

import eu.kanade.presentation.more.stats.StatsTab
import tachiyomi.domain.immersion.model.AnalyticsTitleCoverageFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleFilter
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

class GlassStatsHelpersTest {

    private val utc = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 11, 27)

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0) =
        date.atStartOfDay(utc).plusHours(hour.toLong()).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private val hourMs = 3_600_000L

    @Test
    fun `a session sits at its start hour with its length as a fraction of the day`() {
        val lines = dayLines(listOf(TimedSpan("a", millis(day, 12), hourMs * 2)), listOf(day), utc)
        lines.single().blocks.single().startFraction shouldBe (0.5f plusOrMinus 0.0001f)
        lines.single().blocks.single().widthFraction shouldBe ((2f / 24f) plusOrMinus 0.0001f)
    }

    @Test
    fun `a session crossing midnight is cut and goes on in the next day`() {
        val span = TimedSpan("late", millis(day, 23, 30), hourMs)
        val lines = dayLines(listOf(span), listOf(day.plusDays(1), day), utc)
        val next = lines[0].blocks.single()
        val first = lines[1].blocks.single()
        first.startFraction shouldBe ((23.5f / 24f) plusOrMinus 0.0001f)
        first.widthFraction shouldBe ((0.5f / 24f) plusOrMinus 0.0001f)
        next.startFraction shouldBe 0f
        next.widthFraction shouldBe ((0.5f / 24f) plusOrMinus 0.0001f)
    }

    @Test
    fun `a day with no session has no blocks`() {
        val lines = dayLines(listOf(TimedSpan("a", millis(day, 8), hourMs)), listOf(day.minusDays(1), day), utc)
        lines[0].blocks shouldBe emptyList()
        lines[1].blocks.size shouldBe 1
    }

    @Test
    fun `recent days run newest first, at most seven, down to the oldest loaded day`() {
        recentDays(day, day.minusDays(30)) shouldBe (0..6L).map { day.minusDays(it) }
        recentDays(day, day.minusDays(2)) shouldBe listOf(day, day.minusDays(1), day.minusDays(2))
        recentDays(day, day) shouldBe listOf(day)
    }

    @Test
    fun `the longest time is the full bar and the others are in proportion`() {
        timeBarFractions(listOf(7_200_000L, 3_600_000L, 0L)) shouldBe listOf(1f, 0.5f, 0f)
        timeBarFractions(listOf(0L, 0L)) shouldBe listOf(0f, 0f)
        timeBarFractions(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `the Classic body is shown only while a detail is open on that tab`() {
        showClassicHost(StatsTab.TITLES, titleOpen = true, characterOpen = false, sessionOpen = false) shouldBe true
        showClassicHost(StatsTab.TITLES, titleOpen = false, characterOpen = true, sessionOpen = true) shouldBe false
        showClassicHost(StatsTab.CHARACTERS, titleOpen = false, characterOpen = true, sessionOpen = false) shouldBe true
        showClassicHost(StatsTab.SESSIONS, titleOpen = false, characterOpen = false, sessionOpen = true) shouldBe true
        showClassicHost(StatsTab.ACTIVITY, titleOpen = true, characterOpen = true, sessionOpen = true) shouldBe false
        showClassicHost(StatsTab.GOALS, titleOpen = false, characterOpen = false, sessionOpen = false) shouldBe false
    }

    @Test
    fun `a zero-length session is a block of no width at its start`() {
        val lines = dayLines(listOf(TimedSpan("z", millis(day, 6), 0L)), listOf(day, day.plusDays(1)), utc)
        lines[0].blocks.single().startFraction shouldBe (0.25f plusOrMinus 0.0001f)
        lines[0].blocks.single().widthFraction shouldBe 0f
        lines[1].blocks shouldBe emptyList()
    }

    private fun fact(date: LocalDate, hour: Int, minutes: Long) = SessionFact(millis(date, hour), minutes * 60_000L)

    @Test
    fun `session tiles from every loaded session`() {
        val facts = listOf(fact(day, 21, 10), fact(day, 21, 30), fact(day.minusDays(1), 9, 20), fact(day.minusDays(1), 21, 50))
        val st = sessionStats(facts, hasMore = false, zone = utc)!!
        st.count shouldBe 4
        st.typicalMillis shouldBe 30 * 60_000L // sorted 10, 20, 30, 50: the upper middle
        st.longestMillis shouldBe 50 * 60_000L
        st.longestStartMillis shouldBe millis(day.minusDays(1), 21)
        st.favouriteHour shouldBe 21
        st.perReadingDay shouldBe 2.0
    }

    @Test
    fun `with more pages only the count is shown`() {
        val st = sessionStats(listOf(fact(day, 8, 10), fact(day, 9, 20)), hasMore = true, zone = utc)!!
        st.count shouldBe 2
        st.hasMore shouldBe true
        st.typicalMillis shouldBe null
        st.longestMillis shouldBe null
        st.favouriteHour shouldBe null
        st.perReadingDay shouldBe null
        sessionStats(emptyList(), hasMore = false, zone = utc) shouldBe null
    }

    @Test
    fun `title tiles total the period only without more pages, search or filter`() {
        val none = AnalyticsTitleFilter()
        titleTotalsComplete(true, null, "", none) shouldBe true
        titleTotalsComplete(true, null, "  ", none) shouldBe true
        titleTotalsComplete(true, 100L, "", none) shouldBe false
        titleTotalsComplete(true, null, "frieren", none) shouldBe false
        titleTotalsComplete(true, null, "", none.copy(coverage = AnalyticsTitleCoverageFilter.MISSING)) shouldBe false
        titleTotalsComplete(false, null, "", none) shouldBe false
    }

    @Test
    fun `the forecast tile takes the earliest goal that has a forecast`() {
        // (name, forecast available, projected day)
        val goals = listOf(Triple("a", false, 5L), Triple("b", true, 30L), Triple("c", true, 20L), Triple("d", true, null))
        soonestForecast(goals, { it.second }, { it.third })?.first shouldBe "c"
        soonestForecast(goals.filter { !it.second }, { it.second }, { it.third }) shouldBe null
        soonestForecast(emptyList<Triple<String, Boolean, Long?>>(), { it.second }, { it.third }) shouldBe null
    }

    @Test
    fun `a tap opens the block it is in, else the nearest within reach`() {
        val a = DayBlock("a", 0.10f, 0.02f)
        val b = DayBlock("b", 0.13f, 0.20f)
        nearestBlock(listOf(a, b), 0.11f, 0.02f)?.key shouldBe "a"
        nearestBlock(listOf(a, b), 0.127f, 0.02f)?.key shouldBe "b"
        nearestBlock(listOf(a, b), 0.20f, 0.02f)?.key shouldBe "b"
        nearestBlock(listOf(a, b), 0.09f, 0.02f)?.key shouldBe "a"
        nearestBlock(listOf(a, b), 0.50f, 0.02f) shouldBe null
        nearestBlock(emptyList(), 0.5f, 0.02f) shouldBe null
    }
}
