package chimahon.releases

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/** One row a source listed. [vol] is only set when the source knows it apart from the title (NDL's `dcndl:volume`); [date] null = no exact day. */
data class Listing(
    val title: String,
    val vol: Int?,
    val date: LocalDate?,
    val publisher: String?,
    /** An e-book row (a duplicate of the paper volume with the same date). */
    val ebook: Boolean = false,
    /** Which source listed it (`BooksOrJp.ID`, `NdlSearch.ID`, `KodanshaCalendar.ID`). */
    val source: String = "",
)

/** A source of release dates. Blocking: call it off the main thread. */
interface ReleaseSource {
    val id: String

    /** Everything this source lists for [jpTitle] (a calendar lists everything and ignores it); [today] is JST; [lastKnownVol] lets a source that ranks by relevance ask for the next volume (NDL). The matcher filters. */
    fun listings(jpTitle: String, today: LocalDate, lastKnownVol: Int = 0): List<Listing>
}

/** The page did not look like what the parser knows (layout changed or a bot check): the caller falls back to the next source. */
class ReleaseParseException(message: String) : IOException(message)

class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code $url")

/** The two requests the sources make; tests pass a fake. */
interface ReleaseHttp {
    @Throws(IOException::class) fun get(url: String): String
    @Throws(IOException::class) fun postForm(url: String, form: Map<String, String>): String
}

/** HttpURLConnection with a browser User-Agent and a tiny cookie jar (books.or.jp ties its form token to the session cookie). */
class JdkReleaseHttp(
    private val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
) : ReleaseHttp {
    private val cookies = linkedMapOf<String, String>()

    override fun get(url: String) = call(url, null)

    override fun postForm(url: String, form: Map<String, String>) =
        call(url, form.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") })

    @Synchronized
    private fun call(url: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept-Language", "ja,en;q=0.8")
            if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            c.headerFields["Set-Cookie"]?.forEach { h -> h.substringBefore(';').split('=', limit = 2).let { if (it.size == 2) cookies[it[0].trim()] = it[1] } }
            if (status >= 400) throw HttpStatusException(status, url)
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}

/** `&amp;` `&#34780;` and the double-encoded `&amp;#34780;` books.or.jp sends. */
internal fun unescapeHtml(s: String): String {
    var cur = s
    repeat(2) {
        if ('&' !in cur) return cur
        cur = cur.replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    }
    return cur
}
