package eu.kanade.tachiyomi.ui.glass.discover

import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class NextVolumeTest {

    private fun e(
        id: Long,
        status: String,
        score: Int = 0,
        genres: List<String> = emptyList(),
        avg: Int? = null,
        mediaStatus: String = "FINISHED",
        volumes: Int? = null,
    ) = AniListLibrary.Entry(
        mediaId = id,
        status = status,
        score = score,
        genres = genres,
        averageScore = avg,
        mediaStatus = mediaStatus,
        volumes = volumes,
        native = "t$id",
    )

    // scores of rated titles: 80, 60 → mean 70, sd 10
    private val list = listOf(
        e(1, "COMPLETED", 80, listOf("Comedy")), // v = 1 + 0.3
        e(2, "COMPLETED", 60, listOf("Drama")), // v = -1 + 0.3
        e(3, "DROPPED", 0, listOf("Horror")), // v = 0 - 1.2
        e(11, "PLANNING", genres = listOf("Comedy"), avg = 80, volumes = 10),
        e(12, "PLANNING", genres = listOf("Drama"), avg = 70),
        e(13, "CURRENT", genres = listOf("Horror", "Comedy")),
        e(14, "PAUSED", avg = 60),
        e(15, "PLANNING", genres = listOf("Comedy"), avg = 99, mediaStatus = "CANCELLED"),
    )

    @Test
    fun `weights shrink toward zero`() {
        val w = NextVolumeRanking(list).weights
        w.getValue("Comedy") shouldBe (1.3 / 3 plusOrMinus 1e-9)
        w.getValue("Drama") shouldBe (-0.7 / 3 plusOrMinus 1e-9)
        w.getValue("Horror") shouldBe (-1.2 / 3 plusOrMinus 1e-9)
    }

    @Test
    fun `ranking and match as the page computes them`() {
        val nv = NextVolumeRanking(list)
        // fits 0.433, -0.233, 0.0236, 0; ranks 1.297, -0.498, -0.081, -0.043 (worked by hand from the page's build())
        nv.ranked.map { it.mediaId } shouldContainExactly listOf(11L, 14L, 13L, 12L)
        nv.ranked.map { it.match } shouldContainExactly listOf(100, 75, 50, 25)
        nv.ranked.first { it.mediaId == 13L }.fit shouldBe (0.1 / 3 / Math.sqrt(2.0) plusOrMinus 1e-9)
        nv.ranked.first().rank shouldBe (1.2973 plusOrMinus 1e-3)
        nv.completed shouldBe 2
        nv.dropped shouldBe 1
    }

    @Test
    fun `pick never repeats the last one and skips hidden`() {
        val nv = NextVolumeRanking(list)
        val random = Random(7)
        var last: Long? = null
        repeat(200) {
            val p = nv.pick(hidden = setOf(12L), last = last, random = random)!!
            p.mediaId shouldNotBe last
            p.mediaId shouldNotBe 12L
            last = p.mediaId
        }
        nv.pick(setOf(11L, 12L, 13L, 14L), null, random) shouldBe null
    }

    @Test
    fun `duel of 8 ends with one winner after 7 matches`() {
        val many = (1L..20L).map { e(100 + it, "PLANNING", avg = it.toInt()) }
        var d = NextVolumeRanking(many).duel(emptySet(), Random(3))
        d.all.size shouldBe 8
        var matches = 0
        while (d.winner == null) {
            d = d.choose(first = matches % 2 == 0)
            matches++
        }
        matches shouldBe 7
        d.total shouldBe 7
        d.out.size shouldBe 7
        (d.winner!!.mediaId in d.out) shouldBe false
    }

    @Test
    fun `reasons come from the data`() {
        val nv = NextVolumeRanking(list)
        val p = nv.ranked.first { it.mediaId == 11L }
        nv.why(p, mapOf(11L to "t1")) shouldContainExactly listOf(
            Reason.Recommended("t1"),
            Reason.Taste(listOf("Comedy"), listOf("t1")),
            Reason.Finished(10),
            Reason.Score(80),
        )
        // a negative-weight genre gives no taste line; unknown volume count no "finished" line
        nv.why(nv.ranked.first { it.mediaId == 12L }, emptyMap()) shouldContainExactly listOf(Reason.Score(70))
    }
}
