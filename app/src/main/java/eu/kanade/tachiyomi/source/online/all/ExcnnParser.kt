package eu.kanade.tachiyomi.source.online.all

import org.jsoup.Jsoup

/** excnn.com/manga-raw pages (DataLife Engine, template "anime") -> posts and their hoster links. Pure, for tests. */
object ExcnnParser {
    const val BASE = "https://excnn.com"

    data class Post(val url: String, val title: String, val cover: String?)

    /** [label]: "04-06" from the post's "TITLE 04-06 = excnn20-11-2026a07" line; [link]: the archive on a hoster. */
    data class Volume(val label: String, val link: String, val mirrors: List<String> = emptyList())

    data class Details(val title: String, val cover: String?, val volumes: List<Volume>)

    private val postUrl = Regex("""^https?://(?:www\.)?excnn\.com/manga-raw/\d+-[^/]*\.html$""")

    // "Title (タイトル) 01-06", "... 01", "... 01-02b"
    private val range = Regex("""\s+\d+[a-z]?(?:\s*-\s*\d+[a-z]?)?$""", RegexOption.IGNORE_CASE)

    // "konyakusha ni ... 01-02b = excnn27-12-2025a16"
    private val labelLine = Regex("""(\S+)\s*=\s*([\w.-]+)$""")

    // ".../file/<hex>/excnn20-11-2026a07.rar.html" -> "excnn20-11-2026a07.rar"
    private val archive = Regex("""/([^/]+\.(?:rar|zip|7z|cbz|cbr|cb7|pdf|epub))(?:\.html)?$""", RegexOption.IGNORE_CASE)

    fun isPost(url: String) = postUrl.matches(url.trim())

    /** The post title without the volume range it ends with. */
    fun seriesTitle(title: String) = range.replace(title.trim(), "").ifBlank { title.trim() }

    /** List or search results page -> manga-raw posts, and whether there is a next page. */
    fun parseList(html: String): Pair<List<Post>, Boolean> {
        val doc = Jsoup.parse(html, BASE)
        val posts = doc.select("div.base").mapNotNull { base ->
            val a = base.selectFirst(".heading a[href]") ?: return@mapNotNull null
            val url = a.absUrl("href")
            if (!isPost(url)) return@mapNotNull null // search covers every category
            val cover = base.selectFirst("div.maincont img[src], div.short-story img[src]")?.absUrl("src")
            Post(url, seriesTitle(a.text()), cover)
        }.distinctBy { it.url }
        return posts to (doc.selectFirst("span.nnext a") != null)
    }

    /**
     * Post page -> title, cover and one volume per archive, in page order. Rapidgator wins over another hoster
     * (katfile) carrying the same file, which is kept in [Volume.mirrors]; an archive without a label line is labelled with its filename.
     */
    fun parsePost(html: String): Details {
        val doc = Jsoup.parse(html, BASE)
        val body = doc.selectFirst("div.maincont") ?: error("No post body")
        val labels = body.select("p").mapNotNull { labelLine.find(it.ownText().trim()) }
            .associate { it.groupValues[2] to it.groupValues[1] }
        val volumes = body.select("a[href]").map { it.absUrl("href") }
            .mapNotNull { url -> archive.find(url)?.let { it.groupValues[1] to url } }
            .groupBy({ it.first }, { it.second })
            .map { (file, urls) ->
                // ponytail: rapidgator hardcoded as the Real-Debrid host; isLink (RD's host list) needs the network
                val link = urls.firstOrNull { "rapidgator." in it } ?: urls.first()
                Volume(labels[file.substringBeforeLast('.')] ?: file, link, urls.distinct() - link)
            }
        val title = doc.selectFirst("h1.heading")?.text().orEmpty()
        return Details(seriesTitle(title), body.selectFirst("img[src]")?.absUrl("src"), volumes)
    }
}
