package eu.kanade.tachiyomi.source.online.all

import org.jsoup.Jsoup
import java.util.Base64

/** x3dl.net (WordPress, theme MH) list/search and post pages -> posts and Uploady links. Pure, for tests. */
object X3dlParser {
    const val BASE = "https://x3dl.net"

    data class Post(val url: String, val title: String, val cover: String?)

    /** [label]: "27", "22-24" from the post's "第27巻" line, else the file name; [link]: https://uploady.io/<id>. */
    data class Volume(val label: String, val link: String)

    data class Details(val title: String, val cover: String?, val volumes: List<Volume>)

    // "[author] title 第01-27巻", "... 全08巻", "... 第01巻"
    private val range = Regex("""\s*(?:第\s*\d+(?:\s*-\s*\d+)?|全\s*\d+)\s*巻$""")
    // "第27巻", "第22-24巻", "第15-21巻 20-21s" (a note after it stays: "15-21 20-21s")
    private val labelLine = Regex("""^第\s*([\d\s-]+?)\s*巻\s*(.*)$""")
    private val size = Regex("""\s*\([^)]*\)$""") // "Draft_King_27.rar (138.86 MB)"

    /** The post title without its 巻 range, keeping "[author] title". */
    fun seriesTitle(title: String) = range.replace(title.trim(), "").ifBlank { title.trim() }

    /** List, category or search page -> posts, and whether the pager has a next link. */
    fun parseList(html: String): Pair<List<Post>, Boolean> {
        val doc = Jsoup.parse(html, "$BASE/wp/")
        val posts = doc.select("article.mh-loop-item").mapNotNull { item ->
            val a = item.selectFirst("h3.entry-title a[href]") ?: return@mapNotNull null
            // absUrl turns the protocol-relative "//i0.wp.com/..." into https
            Post(a.absUrl("href"), seriesTitle(a.text()), item.selectFirst("figure.mh-loop-thumb img[src]")?.absUrl("src"))
        }
        return posts to (doc.selectFirst("a.next.page-numbers") != null)
    }

    /**
     * Post page -> one volume per Uploady link (`link/n?url=` = plain base64 of the hoster link). RapidGator and
     * Hexload links are obfuscated and skipped; an `n?url=` value that isn't an http(s) link (the "Premium"
     * redirector) is skipped too.
     */
    fun parsePost(html: String): Details {
        val doc = Jsoup.parse(html, "$BASE/wp/")
        val title = doc.selectFirst("h1.entry-title")?.text()
            ?: doc.title().substringBefore(" | ")
        val content = doc.selectFirst("div.entry-content") ?: doc.body()
        var label: String? = null
        val volumes = mutableListOf<Volume>()
        // document order: a "第27巻" paragraph names the links after it
        content.select("p, a[href*=link.x3dl.net/wp/link/n?url=]").forEach { el ->
            if (el.tagName() == "p") {
                labelLine.find(el.text().trim())?.let { label = "${it.groupValues[1]} ${it.groupValues[2]}".trim() }
                return@forEach
            }
            val link = decode(el.absUrl("href").substringAfter("url=")) ?: return@forEach
            if (!link.startsWith("https://") && !link.startsWith("http://")) return@forEach
            val file = size.replace(el.text().trim(), "").ifBlank { link.trimEnd('/').substringAfterLast('/') }
            volumes += Volume(label ?: file, link)
            label = null
        }
        val cover = content.selectFirst("figure.entry-thumbnail img[src]")?.absUrl("src")
        return Details(seriesTitle(title), cover, volumes)
    }

    private fun decode(value: String) = runCatching {
        String(Base64.getDecoder().decode(java.net.URLDecoder.decode(value, "UTF-8")))
    }.getOrNull()
}
