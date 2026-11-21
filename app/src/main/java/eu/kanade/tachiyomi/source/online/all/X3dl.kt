package eu.kanade.tachiyomi.source.online.all

import android.net.Uri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Response

/**
 * x3dl.net manga, built in: a volume is an Uploady link, imported through Real-Debrid like [Excnn]'s.
 * Posts, page 2+ and search sit behind Cloudflare: the app's usual WebView step gets through, nothing here.
 */
class X3dl : HttpSource(), RealDebridSource {
    override val name = "X3DL"
    override val lang = "ja"
    override val baseUrl = X3dlParser.BASE
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int) = GET(
        "$baseUrl/wp/category/manga/normal-manga" + if (page == 1) "" else "/page/$page",
        headers,
    )

    override fun popularMangaParse(response: Response) = mangasPage(response)

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response) = mangasPage(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = GET(
        baseUrl + (if (page == 1) "/" else "/page/$page/") + "?s=" + Uri.encode(query),
        headers,
    )

    override fun searchMangaParse(response: Response) = mangasPage(response)

    private fun mangasPage(response: Response): MangasPage {
        val (posts, hasNext) = X3dlParser.parseList(response.body.string())
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
        val post = X3dlParser.parsePost(response.body.string())
        return SManga.create().apply {
            title = post.title
            thumbnail_url = post.cover
            description = post.volumes.joinToString("\n") { it.label }
            initialized = true
        }
    }

    // The post lists the newest volume first, as sources list chapters
    override fun chapterListParse(response: Response): List<SChapter> {
        val volumes = X3dlParser.parsePost(response.body.string()).volumes
        return volumes.mapIndexed { i, volume ->
            SChapter.create().apply {
                url = volume.link
                name = volume.label
                chapter_number = (volumes.size - i).toFloat()
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter) = chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw Exception("X3DL volumes are imported through Real-Debrid: tap the volume")
}
