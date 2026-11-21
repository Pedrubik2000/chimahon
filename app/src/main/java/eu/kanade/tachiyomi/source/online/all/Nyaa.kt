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
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import rx.Observable

/**
 * nyaa.si "Literature - Raw" through its RSS (the HTML pages come back empty for a plain client). One torrent = one
 * entry with one chapter, its magnet; the info hash rides in the manga url ("/view/<id>#<hash>"), so details and
 * the chapter need no network call and survive a library refresh.
 */
class Nyaa : HttpSource(), RealDebridSource {
    override val name = "Nyaa (raw manga)"
    override val lang = "ja"
    override val baseUrl = "https://nyaa.si"
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/?page=rss&c=3_3&f=0&p=$page", headers)

    override fun popularMangaParse(response: Response) = mangasPage(response)

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response) = mangasPage(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        GET("$baseUrl/?page=rss&c=3_3&f=0&p=$page&q=${Uri.encode(query)}", headers)

    override fun searchMangaParse(response: Response) = mangasPage(response)

    private fun mangasPage(response: Response): MangasPage {
        val entries = NyaaParser.parseRss(response.body.string())
        return MangasPage(
            entries.map { e ->
                SManga.create().apply {
                    url = e.url.removePrefix(baseUrl) + "#" + e.hash
                    title = e.title
                    description = e.description
                    initialized = true
                }
            },
            entries.size == PAGE_SIZE,
        )
    }

    @Deprecated("Use the combined suspend API instead", replaceWith = ReplaceWith("getMangaUpdate"))
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        Observable.just(manga.apply { initialized = true })

    @Deprecated("Use the combined suspend API instead", replaceWith = ReplaceWith("getMangaUpdate"))
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.just(
        listOf(
            SChapter.create().apply {
                url = "magnet:?xt=urn:btih:" + manga.url.substringAfter('#')
                name = manga.title
                chapter_number = 1f
            },
        ),
    )

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url.substringBefore('#')

    override fun getChapterUrl(chapter: SChapter) = chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw Exception("Nyaa torrents are imported through Real-Debrid: tap the volume")

    companion object {
        private const val PAGE_SIZE = 75
    }
}

/** Pure, for tests (kept out of [Nyaa] so a test doesn't load HttpSource). */
object NyaaParser {
    data class Entry(val url: String, val title: String, val hash: String, val description: String)

    /** RSS items -> (view url, title, info hash, "size · N seeders"). */
    fun parseRss(xml: String): List<Entry> =
        Jsoup.parse(xml, "", Parser.xmlParser()).select("item").mapNotNull { item ->
            val hash = item.selectFirst("nyaa|infoHash")?.text()?.lowercase() ?: return@mapNotNull null
            Entry(
                url = item.selectFirst("guid")?.text().orEmpty(),
                title = item.selectFirst("title")?.text().orEmpty(),
                hash = hash,
                description = "${item.selectFirst("nyaa|size")?.text()} · " +
                    "${item.selectFirst("nyaa|seeders")?.text()} seeders",
            )
        }
}
