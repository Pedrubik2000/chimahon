package chimahon.releases

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class ReleaseStoreTest {
    @TempDir lateinit var dir: File

    private val sample = ReleaseState(
        series = listOf(
            WatchedSeries(1, "ダンダダン", "集英社", 25, WatchStatus.READING, true),
            WatchedSeries(2, "葬送のフリーレン", null, 14, WatchStatus.PLAN, null),
        ),
        rows = listOf(
            VolumeRow(1, 26, LocalDate.parse("2026-11-04"), VolState.ON_SOURCES, "books.or.jp", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-05"), LocalDate.parse("2026-10-28"), mapOf("books.or.jp" to LocalDate.parse("2026-11-04"), "ndl" to LocalDate.parse("2026-11-05"))),
            VolumeRow(2, 15, null, VolState.ANNOUNCED, null, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-05")),
        ),
        notified = setOf("1:26:OUT_TODAY", "1:26:DATE_CHANGED:2026-11-04"),
    )

    @Test fun roundTripThroughTheFile() {
        val f = File(dir, "sub/releases.json")
        ReleaseStore(f).update { sample }
        assertEquals(sample, ReleaseStore(f).get())
        assertTrue(f.isFile)
        assertTrue(dir.walk().none { it.name.endsWith(".tmp") })
    }

    @Test fun fileWithoutPerSourceDatesStillLoads() {
        val f = File(dir, "old.json")
        val j = ReleaseStore.toJson(sample)
        j.getJSONArray("rows").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).remove("dates") }
        f.writeText(j.toString())
        assertEquals(sample.rows.map { it.copy(dates = emptyMap()) }, ReleaseStore(f).get().rows)
    }

    @Test fun failedWriteLeavesTheCachedStateAlone() {
        val f = File(dir, "r.json")
        val s = ReleaseStore(f)
        s.update { sample }
        File(dir, "r.json.tmp").mkdir() // the temp file cannot be written
        assertTrue(runCatching { s.update { ReleaseState() } }.isFailure)
        assertEquals(sample, s.get())
        assertEquals(sample, ReleaseStore(f).get())
    }

    @Test fun twoInstancesOnOneFileDoNotLoseWrites() {
        val f = File(dir, "r.json")
        val a = ReleaseStore(f)
        val b = ReleaseStore(f)
        a.update { it.copy(series = it.series + sample.series[0]) }
        b.update { it.copy(series = it.series + sample.series[1]) }
        assertEquals(sample.series, ReleaseStore(f).get().series)
    }

    @Test fun missingFileStartsEmpty() {
        assertEquals(ReleaseState(), ReleaseStore(File(dir, "none.json")).get())
    }

    @Test fun updateSeesTheLatestState() {
        val s = ReleaseStore(File(dir, "r.json"))
        s.update { it.copy(series = it.series + sample.series[0]) }
        s.update { it.copy(series = it.series + sample.series[1]) }
        assertEquals(sample.series, ReleaseStore(File(dir, "r.json")).get().series)
    }

    @Test fun corruptFileIsKeptAsideAndStartsEmpty() {
        val f = File(dir, "r.json")
        f.writeText("{ not json at all", Charsets.UTF_8)
        val logs = mutableListOf<String>()
        val s = ReleaseStore(f, logs::add)
        assertEquals(ReleaseState(), s.get())
        assertTrue(logs.single().contains("kept as r.json.bad-"))
        val bad = dir.listFiles()!!.single { it.name.startsWith("r.json.bad-") }
        assertEquals("{ not json at all", bad.readText())
        // it works afterwards, and the bad copy is untouched
        s.update { sample }
        assertEquals(sample, ReleaseStore(f).get())
        assertEquals("{ not json at all", bad.readText())
    }

    @Test fun wrongFormatAndBrokenRowAreCorrupt() {
        for (text in listOf("""{"format":"other"}""", """{"format":"kumahon-releases-1","series":[{"anilistId":1}],"rows":[],"notified":[]}""")) {
            val f = File(dir, "x${text.length}.json")
            f.writeText(text)
            assertEquals(ReleaseState(), ReleaseStore(f).get())
            assertTrue(!f.exists())
        }
    }
}
