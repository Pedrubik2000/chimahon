package eu.kanade.tachiyomi.source.online.all

import org.jsoup.Jsoup
import java.util.Base64

/** x3dl.net (WordPress, theme MH) list/search and post pages -> posts and Uploady links. Pure, for tests. */
object X3dlParser {
    const val BASE = "https://x3dl.net"

    data class Post(val url: String, val title: String, val cover: String?)

    /** [label]: "27", "22-24" from the post's "第27巻" line, else the file name; [link]: https://uploady.io/<id>. */
    /** [mirrors]: the same file on the post's other hosters (RapidGator, frdl.io, upload42...). */
    data class Volume(val label: String, val link: String, val mirrors: List<String> = emptyList())

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
     * Post page -> one volume per (label paragraph, file), in page order. Each hoster link belongs to the nearest
     * "第…巻" paragraph above it. Uploady (`link/n?url=` = plain base64) wins, else RapidGator (`wp-ads3?url=`,
     * see [rapidGator]); a file on neither gives no volume (Real-Debrid may not take its hoster). The other
     * `link/n` hosts of a kept file are its [Volume.mirrors]; the "Premium" redirector (no https) is skipped.
     */
    fun parsePost(html: String): Details {
        val doc = Jsoup.parse(html, "$BASE/wp/")
        val title = doc.selectFirst("h1.entry-title")?.text()
            ?: doc.title().substringBefore(" | ")
        val content = doc.selectFirst("div.entry-content") ?: doc.body()
        var label: String? = null
        // (label, file) -> links found, any hoster
        val found = linkedMapOf<Pair<String?, String>, MutableList<String>>()
        content.select("p, a[href*=link.x3dl.net/wp/link/n?url=], a[href*=/wp/zip/wp-ads3?url=]").forEach { el ->
            if (el.tagName() == "p") {
                labelLine.find(el.text().trim())?.let { label = "${it.groupValues[1]} ${it.groupValues[2]}".trim() }
                return@forEach
            }
            val value = el.attr("href").substringAfter("url=")
            val link = if ("wp-ads3" in el.attr("href")) {
                rapidGator(value)
            } else {
                base64(value)?.let(::String)?.takeIf { it.startsWith("https://") }
            } ?: return@forEach
            val file = size.replace(el.text().trim(), "").ifBlank { link.trimEnd('/').substringAfterLast('/') }
            found.getOrPut(label to file) { mutableListOf() } += link
        }
        val kept = found.mapNotNull { (key, links) ->
            val main = links.firstOrNull { it.startsWith("https://uploady.io/") }
                ?: links.firstOrNull { rapidGatorLink.containsMatchIn(it) }
                ?: return@mapNotNull null
            key to listOf(main) + (links.distinct() - main)
        }
        val perLabel = kept.groupingBy { it.first.first }.eachCount()
        val volumes = kept.map { (key, links) ->
            val (label, file) = key
            val name = when {
                label == null -> file
                perLabel.getValue(label) > 1 -> "$label $file"
                else -> label
            }
            Volume(name, links.first(), links.drop(1))
        }
        val cover = content.selectFirst("figure.entry-thumbnail img[src]")?.absUrl("src")
        return Details(seriesTitle(title), cover, volumes)
    }

    private val rapidGatorLink = Regex("""^https://(rg\.to|rapidgator\.net)/file/[0-9a-f]{32}""")

    // ponytail: fixed site key (same on every post seen 2026-11-21); if the site changes it these links fail the
    // check and their RapidGator-only volumes just disappear again
    private val key = "f91f4d061c663e30e058cb62bd7e4ec5".toByteArray()

    /** wp-ads3 value: base64, then each byte minus the key byte (mod 256) -> https://rg.to/file/<id>, else null. */
    fun rapidGator(value: String): String? {
        val c = base64(value) ?: return null
        val plain = String(ByteArray(c.size) { i -> (c[i] - key[i % key.size]).toByte() }, Charsets.ISO_8859_1)
        return rapidGatorLink.find(plain)?.value
    }

    // either alphabet ("-" shows up in wp-ads3 values), padding optional
    private fun base64(value: String) = runCatching {
        Base64.getDecoder().decode(java.net.URLDecoder.decode(value, "UTF-8").replace('-', '+').replace('_', '/'))
    }.getOrNull()
}
