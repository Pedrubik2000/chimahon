package eu.kanade.tachiyomi.source.online.all

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WpRawParserTest {

    private fun fixture(name: String) = javaClass.getResource("/wpraw/$name")!!.readText()

    private val allowed = Regex(
        """^https?://(?:www\.)?(rapidgator\.net|rg\.to|katfile\.[a-z]+|ex-load\.com|uploady\.io|ddownload\.com|ddl\.to|sakurafile\.com)/""",
    )

    // fixture number -> site, least volumes its post fixture must give
    private val sites = listOf(
        Triple(11, WpRaw.MANGA_ZONE, 1),
        Triple(16, WpRaw.BSZIP, 1),
        Triple(9, WpRaw.RAWXT, 1),
        Triple(3, WpRaw.A_ZMANGA, 1),
        Triple(4, WpRaw.MANGA_ZIP, 14),
    )

    @Test
    fun `list, page 2, search and post of each blog`() {
        sites.forEach { (n, site, minVolumes) ->
            val (posts, hasNext) = WpRawParser.parseList(site, fixture("s$n.html"))
            (posts.isNotEmpty() && hasNext) shouldBe true
            posts.all { it.title.isNotBlank() && it.url.startsWith("http") } shouldBe true
            WpRawParser.parseList(site, fixture("pg$n.html")).second shouldBe true
            val (found, _) = WpRawParser.parseList(site, fixture("q$n.html"))
            val post = WpRawParser.parsePost(site, fixture("p$n.html"))
            (post.volumes.size >= minVolumes) shouldBe true
            post.volumes.all { allowed.containsMatchIn(it.link) } shouldBe true
            post.volumes.map { it.label }.distinct().size shouldBe post.volumes.size
            println(
                "${site.baseUrl}: list ${posts.size} (first '${posts.first().title}'), search ${found.size}" +
                    found.joinToString(prefix = " [", postfix = "]") { it.title } +
                    ", post '${post.title}' ${post.volumes.size} volumes: " +
                    post.volumes.take(8).joinToString { "${it.label} = ${it.link.substringAfter("://").substringBefore('/')}" },
            )
        }
        WpRawParser.seriesTitle("ドラフトキング raw 第01-27巻 [Dorafuto Kingu vol 01-27]") shouldBe "ドラフトキング"
        WpRawParser.seriesTitle("[松田あき] 地下アイドルを影で支える俺 全03巻") shouldBe "[松田あき] 地下アイドルを影で支える俺"
        WpRawParser.seriesTitle("Title v01-05") shouldBe "Title"
        val zip = WpRawParser.parsePost(WpRaw.MANGA_ZIP, fixture("p4.html"))
        zip.title shouldBe "マッシュル-MASHLE-"
        zip.volumes.first().label shouldBe "Masshuru_v01-03s"
        VolumeLabels.volumes(zip.volumes.first().label) shouldBe setOf(1, 2, 3)
        WpRawParser.seriesTitle("[author] Title 01-05") shouldBe "[author] Title"
    }
}
