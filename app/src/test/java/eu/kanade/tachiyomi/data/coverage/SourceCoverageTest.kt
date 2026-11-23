package eu.kanade.tachiyomi.data.coverage

import eu.kanade.tachiyomi.source.online.all.ExcnnParser
import eu.kanade.tachiyomi.source.online.all.NyaaParser
import eu.kanade.tachiyomi.source.online.all.RealDebridSource
import eu.kanade.tachiyomi.source.online.all.X3dlParser
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceCoverageTest {

    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test
    fun `post titles match the entry, nothing else`() {
        // Excnn: romanized title, the Japanese one in parentheses
        val excnn = ExcnnParser.parseList(fixture("excnn/list.html")).first.map { it.title }
        val konyakusha = listOf("婚約者に浮気された令嬢は異国の強面盟主に溺愛される", "Konyakusha ni Uwaki Sareta Reijou")
        excnn.count { CoverageMatch.matches(konyakusha, it) } shouldBe 1
        CoverageMatch.matches(konyakusha, excnn[6]) shouldBe true

        // X3DL: "[author] title"
        val x3dl = X3dlParser.parseList(fixture("x3dl/list.html")).first.map { it.title }
        CoverageMatch.matches(listOf("ドラフトキング", "Draft King"), x3dl[0]) shouldBe true
        CoverageMatch.matches(listOf("ドラフト"), x3dl[0]) shouldBe false

        // Nyaa: "title 第01-06巻 [romaji vol 01-06]"
        val nyaa = NyaaParser.parseRss(fixture("nyaa_rss.xml")).map { it.title }
        nyaa.count { CoverageMatch.matches(listOf("スタジオカバナ"), it) } shouldBe 1
        nyaa.count { CoverageMatch.matches(listOf("Aoi Hana"), it) } shouldBe 1
        // a trailing number is part of the title: ACT 4 is not ACT 3
        nyaa.count { CoverageMatch.matches(listOf("DEAR BOYS ACT 4"), it) } shouldBe 1
        nyaa.count { CoverageMatch.matches(listOf("DEAR BOYS ACT 3"), it) } shouldBe 0
        nyaa.count { CoverageMatch.matches(listOf("DEAR BOYS"), it) } shouldBe 0
    }

    @Test
    fun `a file with the s mark is the last choice for Real-Debrid too`() {
        // inside one source: the unmarked pack wins although the marked file is smaller
        CoverageMatch.volumes(listOf("Title_v01-02s" to "s", "Title_v01-05" to "p", "Title_v06s" to "only")) shouldBe
            mapOf(1 to "p", 2 to "p", 3 to "p", 4 to "p", 5 to "p", 6 to "only")
        // across sources: the first source's link is of a marked file, so the second source's goes first
        val marked = Coverage(
            1, "A", Coverage.State.FOUND, volumes = mapOf(1 to "s"),
            files = listOf(Coverage.File(listOf(1, 2, 3), listOf("s"), scans = true)),
        )
        val plain = Coverage(2, "B", Coverage.State.FOUND, volumes = mapOf(1 to "p"))
        CoverageMatch.links(1, listOf(marked, plain)) shouldBe listOf(2L to "p", 1L to "s")
    }

    @Test
    fun `volumes from chapters, single files before batches`() {
        val volumes = CoverageMatch.volumes(
            listOf(
                "15-21 20-21s" to "d",
                "01-14 01-04別スキャン" to "a",
                "02" to "c",
                "01" to "b",
                "Shonen_Jump_2026-04-05" to "x",
            ),
        )
        volumes[1] shouldBe "b"
        volumes[2] shouldBe "c"
        (3..14).all { volumes[it] == "a" } shouldBe true
        (15..21).all { volumes[it] == "d" } shouldBe true
        volumes.keys shouldBe (1..21).toSet()
    }

    @Test
    fun `merge follows the source order`() {
        val x3dl = Coverage(1, "X3DL", Coverage.State.FOUND, volumes = (1..14).associateWith { "x-batch" } + (15 to "x15"))
        val excnn = Coverage(2, "Excnn", Coverage.State.FOUND, volumes = mapOf(1 to "e1", 2 to "e2", 16 to "e16"))
        val nyaa = Coverage(3, "Nyaa", Coverage.State.NOT_FOUND)
        val gated = Coverage(4, "Gated", Coverage.State.NEEDS_VISIT, volumes = mapOf(1 to "g1"))
        val inOrder = CoverageMatch.ordered(listOf(nyaa, excnn, gated, x3dl), listOf(1L, 2L, 3L))
        inOrder.map { it.sourceId } shouldBe listOf(1L, 2L, 3L, 4L)

        CoverageMatch.links(1, inOrder) shouldBe listOf(1L to "x-batch", 2L to "e1")
        CoverageMatch.links(16, inOrder) shouldBe listOf(2L to "e16")
        CoverageMatch.links(17, inOrder) shouldBe emptyList()
        CoverageMatch.firstSource(inOrder) shouldBe (1..15).associateWith { 1L } + (16 to 2L)

        // the other order: Excnn first
        val excnnFirst = CoverageMatch.ordered(inOrder, listOf(2L, 1L))
        CoverageMatch.links(1, excnnFirst) shouldBe listOf(2L to "e1", 1L to "x-batch")
        CoverageMatch.firstSource(excnnFirst)[3] shouldBe 1L
    }

    @Test
    fun `every file with a volume number is kept with all its links`() {
        val pack = listOf("https://rapidgator.net/file/p", "https://katfile.com/p")
        val files = CoverageMatch.files(
            listOf(
                RealDebridSource.PostFile("03", listOf("https://uploady.io/c")),
                RealDebridSource.PostFile("01-03", pack),
                RealDebridSource.PostFile("extras", listOf("https://uploady.io/x")),
            ),
        )
        CoverageMatch.files(listOf(RealDebridSource.PostFile("第01-02巻", listOf("magnet:?xt=urn:btih:aa"), 7, "120 MiB"))) shouldBe
            listOf(Coverage.File(listOf(1, 2), listOf("magnet:?xt=urn:btih:aa"), 7, "120 MiB"))
        NyaaParser.sizeAndSeeders("120.5 MiB · 7 seeders") shouldBe ("120.5 MiB" to 7)
        NyaaParser.sizeAndSeeders("null · null seeders") shouldBe (null to null)
        files shouldBe listOf(Coverage.File(listOf(3), listOf("https://uploady.io/c")), Coverage.File(listOf(1, 2, 3), pack))
        // a correction learnt on one link holds for its mirrors: they are the same archive
        CoverageMatch.correctedFiles(files, mapOf(pack[0] to setOf(1, 2))) shouldBe
            listOf(files[0], Coverage.File(listOf(1, 2), pack))
        CoverageMatch.correctedFiles(files, mapOf("https://uploady.io/c" to emptySet())) shouldBe listOf(files[1])
        CoverageMatch.correctedFiles(files, emptyMap()) shouldBe files
    }

    @Test
    fun ranges() {
        CoverageMatch.ranges((1..26).toList() + 28) shouldBe "1–26, 28"
        CoverageMatch.ranges(listOf(3)) shouldBe "3"
        CoverageMatch.ranges(listOf(5, 1, 2)) shouldBe "1–2, 5"
        CoverageMatch.ranges(emptyList()) shouldBe ""
    }

    @Test
    fun `a corrected link no longer gives the volumes it did not hold`() {
        val pack = "https://x3dl.example/MASHLE_01s-05.rar"
        val v06 = "https://x3dl.example/v06.rar"
        val volumes = mapOf(1 to pack, 2 to pack, 3 to pack, 4 to pack, 5 to pack, 6 to v06)
        val fixed = CoverageMatch.corrected(volumes, mapOf(pack to setOf(3, 4, 5)))
        fixed shouldBe mapOf(3 to pack, 4 to pack, 5 to pack, 6 to v06)
        CoverageMatch.corrected(volumes, emptyMap()) shouldBe volumes
        CoverageMatch.corrected(volumes, mapOf("https://other.example/a.rar" to setOf(1))) shouldBe volumes
        // after the correction, vol. 1 comes from the next source
        val x3dl = Coverage(1, "X3DL", Coverage.State.FOUND, volumes = fixed)
        val excnn = Coverage(2, "Excnn", Coverage.State.FOUND, volumes = mapOf(1 to "https://excnn.example/v01.rar"))
        CoverageMatch.links(1, listOf(x3dl, excnn)) shouldBe listOf(2L to "https://excnn.example/v01.rar")
    }
}
