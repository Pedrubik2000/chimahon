package eu.kanade.tachiyomi.source.online.all

import org.jsoup.Jsoup
import java.net.URLDecoder

/**
 * WordPress raw-manga blogs whose posts carry plain hoster links (manga-zone, bszip, rawxt-scan, a-zmanga).
 * Pure, for tests. Selectors that differ per site come in [WpRawParser.Site].
 */
object WpRawParser {
    data class Site(
        val baseUrl: String,
        /** Post title link inside a list item. */
        val titleLink: String = "h2.entry-title a[href]",
        val cover: String = "img[src]",
        /** The post lists its oldest volume first (reversed so the newest is on top, as sources list chapters). */
        val oldestFirst: Boolean = false,
    )

    data class Post(val url: String, val title: String, val cover: String?)
    data class Volume(val label: String, val link: String)
    data class Details(val title: String, val cover: String?, val volumes: List<Volume>)

    // first = preferred; rapidgator also as rg.to, katfile on any TLD
    private val hosts = listOf(
        Regex("""(?:rapidgator\.net|rg\.to)/file/"""),
        Regex("""katfile\.[a-z]+/[a-z0-9]{8,}"""),
        Regex("""ex-load\.com/[a-z0-9]{8,}"""),
        Regex("""uploady\.io/[a-z0-9]{8,}"""),
        Regex("""ddownload\.com/[a-z0-9]{8,}"""),
        Regex("""ddl\.to/[a-z0-9]{8,}"""),
    )
    private val hosterLink = Regex("""^https?://(?:www\.)?([^/]+/[^?#]*)""", RegexOption.IGNORE_CASE)
    private val archive = Regex("""\.(?:rar|zip|7z|cbz|cbr|pdf|epub)$""", RegexOption.IGNORE_CASE)
    private val size = Regex("""\s*(?:[–\-]\s*[\d.]+\s*[KMG]i?B|\([^)]*\))\s*$""", RegexOption.IGNORE_CASE)

    // "Title raw 第01-27巻 [Title vol 01-27]", "[author] Title 全03巻", "Title v01-05", "[author] Title 01-05"
    private val alias = Regex("""\s*\[[^\]]*]$""")
    private val range = Regex(
        """\s*(?:第\s*\d+(?:\s*-\s*\d+)?\s*巻|全\s*\d+\s*巻|v(?:ol\.?)?\s*\d+(?:\s*-\s*\d+)?|\d+\s*-\s*\d+|0\d+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val raw = Regex("""\s+raw$""", RegexOption.IGNORE_CASE)

    fun seriesTitle(title: String): String {
        val t = title.trim()
        val s = raw.replace(range.replace(raw.replace(alias.replace(t, "").ifBlank { t }, ""), ""), "").trim()
        return s.ifBlank { t }
    }

    fun parseList(site: Site, html: String): Pair<List<Post>, Boolean> {
        val doc = Jsoup.parse(html, site.baseUrl + "/")
        val posts = doc.select("article, div.post").mapNotNull { item ->
            val a = item.selectFirst(site.titleLink) ?: return@mapNotNull null
            Post(a.absUrl("href"), seriesTitle(a.text()), item.selectFirst(site.cover)?.absUrl("src"))
        }.distinctBy { it.url }
        // manga-zone, bszip, a-zmanga: page numbers; rawxt-scan: "Older posts"
        val next = doc.selectFirst("a.next.page-numbers, a[rel=next], .nav-previous a[href]") != null
        return posts to next
    }

    /** One volume per file (mirrors grouped by file name), first hoster in [hosts] order. */
    fun parsePost(site: Site, html: String): Details {
        val doc = Jsoup.parse(html, site.baseUrl + "/")
        val title = doc.selectFirst("h1.entry-title")?.text()
            ?: doc.selectFirst("div.post-main h1 a")?.text()
            ?: doc.title().substringBefore(" | ")
        val body = doc.selectFirst("div.entry-content, div.post-main") ?: doc.body()
        val files = linkedMapOf<String, MutableList<Pair<Int, String>>>()
        body.select("a[href]").forEach { a ->
            val url = a.absUrl("href")
            val path = hosterLink.find(url)?.groupValues?.get(1) ?: return@forEach
            val rank = hosts.indexOfFirst { it.containsMatchIn(path.lowercase()) }.takeIf { it >= 0 } ?: return@forEach
            files.getOrPut(fileName(url, a.text())) { mutableListOf() } += rank to url
        }
        val volumes = files.map { (file, links) ->
            Volume(archive.replace(file, ""), links.minBy { it.first }.second)
        }
        return Details(
            seriesTitle(title),
            body.selectFirst("img[src]")?.absUrl("src"),
            if (site.oldestFirst) volumes.reversed() else volumes,
        )
    }

    /** The URL's file name ("…/Title_01.rar.html"), else the link text without its size, else the file id. */
    private fun fileName(url: String, text: String): String {
        val segments = url.substringBefore('?').trimEnd('/').split('/')
        val last = runCatching { URLDecoder.decode(segments.last().replace("+", "%2B"), "UTF-8") }
            .getOrDefault(segments.last()).removeSuffix(".html")
        if (archive.containsMatchIn(last)) return last
        return size.replace(text.trim(), "").ifBlank { last }
    }
}
