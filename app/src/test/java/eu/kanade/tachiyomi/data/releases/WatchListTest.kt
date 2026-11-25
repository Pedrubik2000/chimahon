package eu.kanade.tachiyomi.data.releases

import chimahon.releases.VolState
import chimahon.releases.VolumeRow
import chimahon.releases.WatchStatus
import chimahon.releases.WatchedSeries
import eu.kanade.tachiyomi.data.anilist.AniListLibrary.Entry
import eu.kanade.tachiyomi.data.coverage.Coverage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class WatchListTest {

    private val today = LocalDate.parse("2026-11-25")

    private fun entry(
        id: Long,
        status: String = "CURRENT",
        native: String? = "タイトル$id",
        progressVolumes: Int = 0,
        volumes: Int? = null,
        mediaStatus: String? = "RELEASING",
        format: String? = "MANGA",
    ) = Entry(id, status, progressVolumes = progressVolumes, native = native, volumes = volumes, mediaStatus = mediaStatus, format = format)

    private fun row(id: Long, vol: Int, date: String?, state: VolState) =
        VolumeRow(id, vol, date?.let(LocalDate::parse), state, null, today, today)

    @Test
    fun `only reading is watched, plan to read and the other list statuses are not`() {
        val list = WatchList.build(
            listOf(entry(1, "CURRENT"), entry(2, "PLANNING"), entry(3, "REPEATING"), entry(4, "COMPLETED"), entry(5, "PAUSED"), entry(6, "DROPPED")),
            emptyMap(), emptyList(),
        )
        list.map { it.anilistId to it.status } shouldBe listOf(1L to WatchStatus.READING, 3L to WatchStatus.READING)
    }

    @Test
    fun `finished with a known count already reached stops being watched`() {
        val list = WatchList.build(
            listOf(
                entry(1, mediaStatus = "FINISHED", volumes = 10, progressVolumes = 10), // done: progress reached it
                entry(2, mediaStatus = "FINISHED", volumes = 10, progressVolumes = 3),  // done: all 10 are on the tablet
                entry(3, mediaStatus = "FINISHED", volumes = 10, progressVolumes = 3),  // not done: 8 here
                entry(4, mediaStatus = "FINISHED", volumes = null, progressVolumes = 3), // count unknown: keep
                entry(5, mediaStatus = "RELEASING", volumes = 5, progressVolumes = 5),  // still publishing: keep
            ),
            mapOf(2L to 10, 3L to 8), emptyList(),
        )
        list.map { it.anilistId } shouldBe listOf(3L, 4L, 5L)
    }

    @Test
    fun `every skipped reading entry comes back with its reason`() {
        val (list, skipped) = WatchList.buildWithSkips(
            listOf(
                entry(1, format = "NOVEL"), entry(2, format = "ONE_SHOT"), entry(3, native = null),
                entry(4, mediaStatus = "FINISHED", volumes = 3, progressVolumes = 3), entry(5), entry(6, "PLANNING"),
            ),
            emptyMap(), emptyList(),
        )
        list.map { it.anilistId } shouldBe listOf(5L)
        skipped.map { it.mediaId to it.reason } shouldBe listOf(1L to "NOVEL", 2L to "ONE_SHOT", 3L to "NO_NATIVE_TITLE", 4L to "FINISHED_COVERED")
    }

    @Test
    fun `novels one-shots and titles without a native name are skipped`() {
        val list = WatchList.build(
            listOf(entry(1, format = "NOVEL"), entry(2, format = "ONE_SHOT"), entry(3, native = null), entry(4, native = " "), entry(5, format = null)),
            emptyMap(), emptyList(),
        )
        list.map { it.anilistId } shouldBe listOf(5L)
    }

    @Test
    fun `lastKnownVol is the higher of the tablet and AniList progress`() {
        WatchList.lastKnownVol(null, 0) shouldBe 0
        WatchList.lastKnownVol(12, 9) shouldBe 12
        WatchList.lastKnownVol(4, 9) shouldBe 9
        val list = WatchList.build(listOf(entry(1, progressVolumes = 9), entry(2, progressVolumes = 1)), mapOf(1L to 4, 2L to 7), emptyList())
        list.map { it.lastKnownVol } shouldBe listOf(9, 7)
    }

    @Test
    fun `a stored title override, publisher and auto-fetch are kept`() {
        val stored = listOf(WatchedSeries(1, "手直しした題", "講談社", 2, WatchStatus.PLAN, false))
        val list = WatchList.build(listOf(entry(1, native = "AniList native", progressVolumes = 6)), emptyMap(), stored)
        list shouldBe listOf(WatchedSeries(1, "手直しした題", "講談社", 6, WatchStatus.READING, false))
    }

    @Test
    fun `daily listings - a date within a week or no date, not a far one`() {
        val series = (1L..5L).map { WatchedSeries(it, "t$it", lastKnownVol = 3) }
        val rows = listOf(
            row(1, 4, "2026-11-27", VolState.ANNOUNCED), // in the next 7 days
            row(2, 4, "2026-12-20", VolState.ANNOUNCED), // far
            row(3, 4, "2026-11-20", VolState.RELEASED),  // in the last 7 days
            row(4, 4, "2026-10-01", VolState.ON_SOURCES), // old, nothing to learn
            // 5: no row at all = "No date"
            row(3, 2, "2026-11-26", VolState.ANNOUNCED), // below lastKnownVol: ignored
        )
        WatchList.dailyListings(series, rows, today) shouldBe setOf(1L, 3L, 5L)
    }

    @Test
    fun `daily coverage - only reading series whose next volume is out or undated`() {
        val series = listOf(
            WatchedSeries(1, "a", lastKnownVol = 3),
            WatchedSeries(2, "b", lastKnownVol = 3),
            WatchedSeries(3, "c", lastKnownVol = 3),
            WatchedSeries(4, "d", lastKnownVol = 3),
            WatchedSeries(5, "e", lastKnownVol = 3, status = WatchStatus.PLAN),
            WatchedSeries(6, "f", lastKnownVol = 3),
        )
        val rows = listOf(
            row(1, 4, "2026-11-25", VolState.RELEASED),    // out today: look
            row(2, 4, "2026-12-25", VolState.ANNOUNCED),   // future: skip
            row(3, 4, "2026-11-01", VolState.ON_TABLET),   // already here: skip
            row(4, 4, null, VolState.ANNOUNCED),           // no date: look
            row(5, 4, "2026-11-01", VolState.RELEASED),    // plan to read: skip
            // 6: nothing known = No date lane: look
        )
        WatchList.dailyCoverage(series, rows, today) shouldBe setOf(1L, 4L, 6L)
        WatchList.forceCoverage(series[0], rows, today) shouldBe true
        WatchList.forceCoverage(series[3], rows, today) shouldBe false
        WatchList.forceCoverage(series[5], rows, today) shouldBe false
    }

    @Test
    fun `a read volume above the last known one is not waiting`() {
        val s = WatchedSeries(1, "a", lastKnownVol = 3)
        WatchList.dailyCoverage(listOf(s), listOf(row(1, 4, "2026-11-01", VolState.READ)), today) shouldBe setOf(1L) // nothing open: look for the next
        WatchList.dailyCoverage(listOf(s), listOf(row(1, 4, "2026-11-01", VolState.ON_TABLET)), today) shouldBe emptySet()
    }

    @Test
    fun `a fresh read is forced only for a date that passed within a week`() {
        val s = WatchedSeries(1, "a", lastKnownVol = 3)
        WatchList.forceCoverage(s, listOf(row(1, 4, "2026-11-18", VolState.RELEASED)), today) shouldBe true  // 7 days
        WatchList.forceCoverage(s, listOf(row(1, 4, "2026-11-17", VolState.RELEASED)), today) shouldBe false // 8 days: cache
        WatchList.forceCoverage(s, listOf(row(1, 4, "2026-11-26", VolState.ANNOUNCED)), today) shouldBe false // future
    }

    @Test
    fun `best source per volume follows the source order`() {
        val a = Coverage(1, "Excnn", Coverage.State.FOUND, volumes = mapOf(4 to "l", 5 to "l"))
        val b = Coverage(2, "X3DL", Coverage.State.FOUND, volumes = mapOf(5 to "m", 6 to "m"))
        val c = Coverage(3, "Nyaa", Coverage.State.ERROR, volumes = mapOf(7 to "n"))
        WatchList.bestSources(listOf(a, b, c), listOf(2, 1, 3)) shouldBe mapOf(4 to "Excnn", 5 to "X3DL", 6 to "X3DL")
    }

    @Test
    fun `nine o'clock comes today before nine, tomorrow after`() {
        ReleasesWorker.delayUntilNine(java.time.LocalDateTime.parse("2026-11-25T08:30:00")).toMinutes() shouldBe 30
        ReleasesWorker.delayUntilNine(java.time.LocalDateTime.parse("2026-11-25T09:00:00")).toHours() shouldBe 24
        ReleasesWorker.delayUntilNine(java.time.LocalDateTime.parse("2026-11-25T21:00:00")).toHours() shouldBe 12
    }
}
