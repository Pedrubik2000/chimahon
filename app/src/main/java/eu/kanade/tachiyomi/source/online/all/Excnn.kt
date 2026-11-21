package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import eu.kanade.tachiyomi.data.download.RealDebridImportJob
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.toast
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * excnn.com/manga-raw, built in: a volume is a hoster link, so opening or downloading it goes through
 * [RealDebridImportJob] into local manga ([importIfExcnn]) instead of the reader or the downloader.
 */
class Excnn : HttpSource() {
    override val name = "Excnn"
    override val lang = "ja"
    override val baseUrl = ExcnnParser.BASE
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int) =
        GET(if (page == 1) "$baseUrl/manga-raw/" else "$baseUrl/manga-raw/page/$page/", headers)

    override fun popularMangaParse(response: Response) = mangasPage(response)

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response) = mangasPage(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val form = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("story", query)
            .add("search_start", page.toString())
            .add("result_from", ((page - 1) * 10 + 1).toString())
            .build()
        return POST("$baseUrl/index.php?do=search", headers, form)
    }

    override fun searchMangaParse(response: Response) = mangasPage(response)

    private fun mangasPage(response: Response): MangasPage {
        val (posts, hasNext) = ExcnnParser.parseList(response.body.string())
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
        val post = ExcnnParser.parsePost(response.body.string())
        return SManga.create().apply {
            title = post.title
            thumbnail_url = post.cover
            description = post.volumes.joinToString("\n") { it.label }
            initialized = true
        }
    }

    // Newest volume first, as sources list chapters
    override fun chapterListParse(response: Response): List<SChapter> =
        ExcnnParser.parsePost(response.body.string()).volumes.mapIndexed { i, volume ->
            SChapter.create().apply {
                url = volume.link
                name = volume.label
                chapter_number = (i + 1).toFloat()
            }
        }.reversed()

    override fun getChapterUrl(chapter: SChapter) = chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw Exception("Excnn volumes are imported through Real-Debrid: tap the volume")

    companion object {
        /** Opening or downloading Excnn/X3DL volumes = Real-Debrid imports into local manga; false for others. */
        fun importIfExcnn(context: Context, source: Source?, manga: Manga?, chapters: List<Chapter>): Boolean {
            if ((source !is Excnn && source !is X3dl) || manga == null) return false
            chapters.forEach { RealDebridImportJob.start(context, it.url, manga.title) }
            context.toast("Real-Debrid: downloading to local manga")
            return true
        }
    }
}
