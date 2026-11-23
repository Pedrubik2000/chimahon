package eu.kanade.tachiyomi.source.online.all

import android.net.Uri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Response

/** A WordPress raw-manga blog with plain hoster links (see [WpRawParser]); volumes import through Real-Debrid. */
class WpRaw(
    override val name: String,
    private val site: WpRawParser.Site,
    /** List pages: "" for the front page, else a category path ("/archives/category/..."). */
    private val listPath: String = "",
    /** Added to a list page's address: "?s=" where only the (empty) search lists the posts. */
    private val listQuery: String = "",
) : HttpSource(), RealDebridSource {
    override val lang = "ja"
    override val baseUrl = site.baseUrl
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int) =
        GET(baseUrl + listPath + (if (page == 1) "/" else "/page/$page/") + listQuery, headers)

    override fun popularMangaParse(response: Response) = mangasPage(response)

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response) = mangasPage(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        GET(baseUrl + (if (page == 1) "/" else "/page/$page/") + "?s=" + Uri.encode(query), headers)

    override fun searchMangaParse(response: Response) = mangasPage(response)

    private fun mangasPage(response: Response): MangasPage {
        val (posts, hasNext) = WpRawParser.parseList(site, response.body.string())
        return MangasPage(
            posts.map { post ->
                SManga.create().apply {
                    setUrlWithoutDomain(post.url)
                    title = post.title
                    thumbnail_url = post.cover
                }
            },
            hasNext,
        )
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val post = WpRawParser.parsePost(site, response.body.string())
        return SManga.create().apply {
            title = post.title
            thumbnail_url = post.cover
            description = post.volumes.joinToString("\n") { it.label }
            initialized = true
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val volumes = WpRawParser.parsePost(site, response.body.string()).volumes
        return volumes.mapIndexed { i, volume ->
            SChapter.create().apply {
                url = volume.link
                name = volume.label
                chapter_number = (volumes.size - i).toFloat()
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter) = chapter.url

    override suspend fun files(post: SManga) = client.newCall(chapterListRequest(post)).awaitSuccess().use { response ->
        WpRawParser.parsePost(site, response.body.string()).volumes
            .map { RealDebridSource.PostFile(it.label, listOf(it.link) + it.mirrors) }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw Exception("$name volumes are imported through Real-Debrid: tap the volume")

    companion object {
        val MANGA_ZONE = WpRawParser.Site("https://www.manga-zone.org", oldestFirst = true)
        val BSZIP = WpRawParser.Site(
            "https://bszip.com",
            titleLink = "h1 a[href$=.html]",
            cover = "div.bs-thumbnail img[src]",
        )
        val RAWXT = WpRawParser.Site("http://rawxt-scan.com")
        val A_ZMANGA = WpRawParser.Site("https://www.a-zmanga.net")
        val MANGA_ZIP = WpRawParser.Site("https://manga-zip.is", titleLink = "h2 a[rel=bookmark]")

        fun all() = listOf(
            WpRaw("Manga Zone", MANGA_ZONE),
            WpRaw("BsZip", BSZIP),
            WpRaw("Rawxt", RAWXT),
            WpRaw("A-Z Manga", A_ZMANGA, "/archives/category/%E4%B8%80%E8%88%AC%E6%BC%AB%E7%94%BB"),
            // ponytail: sakurafile only, which Real-Debrid refuses: a Fetch falls through to the next source
            // (hoster error), "Download by hand" uses it. Hide it from Fetch if that wait ever bothers.
            WpRaw("Manga Zip", MANGA_ZIP, listQuery = "?s="),
        )
    }
}
