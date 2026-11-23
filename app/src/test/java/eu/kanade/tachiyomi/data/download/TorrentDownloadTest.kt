package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.Coverage.State
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TorrentDownloadTest {

    private fun file(volumes: IntRange, link: String, seeders: Int? = null, size: String? = null) =
        Coverage.File(volumes.toList(), listOf(link), seeders, size)

    private fun cov(id: Long, vararg f: Coverage.File, state: State = State.FOUND) =
        Coverage(id, "S$id", state, files = f.toList())

    private fun magnets(wanted: Collection<Int>, here: Set<Int>, vararg c: Coverage) =
        TorrentDownload.options(wanted, here, c.toList()).map { it.magnet }

    @Test
    fun `only magnets that hold a wanted volume, from sources that were found`() {
        val c = cov(
            1,
            file(1..2, "https://uploady.io/a"),
            file(1..2, "magnet:?xt=urn:btih:aa", 4, "120 MiB"),
            file(5..6, "magnet:?xt=urn:btih:bb"),
        )
        val lost = cov(2, file(1..2, "magnet:?xt=urn:btih:cc"), state = State.NOT_FOUND)
        val o = TorrentDownload.options(listOf(2), setOf(1), listOf(c, lost)).single()
        o.magnet shouldBe "magnet:?xt=urn:btih:aa"
        o.adds shouldBe listOf(2)
        o.alreadyHere shouldBe listOf(1)
        o.extra shouldBe emptyList()
        o.seeders shouldBe 4
        o.size shouldBe "120 MiB"
    }

    @Test
    fun `shared before dead, then more wanted volumes, then less extra, then more seeders`() {
        val dead = file(1..2, "magnet:dead", 0)
        val partial = file(1..1, "magnet:partial", 50)
        val big = file(1..18, "magnet:big", 30)
        val exactFew = file(1..2, "magnet:exactFew", 2)
        val exactMany = file(1..2, "magnet:exactMany", 9)
        val unknown = file(1..2, "magnet:unknown")
        magnets(listOf(1, 2), emptySet(), cov(1, dead, partial, big, exactFew, exactMany, unknown)) shouldBe
            listOf("magnet:exactMany", "magnet:exactFew", "magnet:unknown", "magnet:big", "magnet:partial", "magnet:dead")
    }

    @Test
    fun `the same torrent listed by two sources is offered once`() {
        magnets(listOf(1), emptySet(), cov(1, file(1..1, "magnet:x", 3)), cov(2, file(1..1, "magnet:x", 3))) shouldBe
            listOf("magnet:x")
    }
}
