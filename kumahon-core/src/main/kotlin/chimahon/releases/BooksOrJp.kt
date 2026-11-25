package chimahon.releases

import java.time.LocalDate

/**
 * books.or.jp (JPRO, the publishers' pre-release catalog): a form POST that needs the `_token` of the home page and its cookie.
 * Results are 20 rows per page, newest first; page 1 is enough for "the next volume". The row's date is the exact 発売（予定）日.
 */
class BooksOrJp(private val http: ReleaseHttp) : ReleaseSource {
    companion object {
        const val ID = "books.or.jp"
        private const val HOME = "https://www.books.or.jp/"
        private const val SEARCH = "https://www.books.or.jp/search-results"

        private val TOKEN = Regex("""name="_token"\s+value="([^"]+)"""")
        private val ITEM = Regex("""class\s*=\s*"result_list_item"""")
        private val TITLE = Regex("""result_list_discription_title">\s*(.*?)\s*</h4>""", RegexOption.DOT_MATCHES_ALL)
        private val PUBLISHER = Regex("""出版社：\s*([^<]*)""")
        private val DATE_HIDDEN = Regex("""publication_date"\s+hidden>\s*(\d{4})-(\d{2})-(\d{2})""")
        private val DATE_TEXT = Regex("""発売（予定）日：\s*(\d{4})年(\d{2})月(\d{2})日""")

        fun parseToken(homeHtml: String): String =
            TOKEN.find(homeHtml)?.groupValues?.get(1) ?: throw ReleaseParseException("books.or.jp: no _token on the home page")

        /** Pure: the rows of one results page. Throws [ReleaseParseException] when it is not a results page at all (an empty result is an empty list). */
        fun parse(html: String): List<Listing> {
            if ("result_title_text" !in html) throw ReleaseParseException("books.or.jp: not a results page")
            return html.split(ITEM).drop(1).mapNotNull { chunk -> runCatching { // a bad row (2027-01-00) is skipped, not the page
                val title = TITLE.find(chunk)?.groupValues?.get(1)?.let(::unescapeHtml)?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null // magazines come without a title
                val d = DATE_HIDDEN.find(chunk) ?: DATE_TEXT.find(chunk)
                val date = d?.let { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
                Listing(
                    title = title,
                    vol = null,
                    date = date,
                    publisher = PUBLISHER.find(chunk)?.groupValues?.get(1)?.trim()?.ifEmpty { null },
                    ebook = "green_box active" !in chunk,
                    source = ID,
                )
            }.getOrNull() }
        }
    }

    override val id = ID
    private var token: String? = null

    override fun listings(jpTitle: String, today: LocalDate, lastKnownVol: Int): List<Listing> {
        val html = try {
            post(jpTitle)
        } catch (e: HttpStatusException) {
            if (e.code != 419) throw e // Laravel: the token/session expired -> one fresh start
            token = null
            post(jpTitle)
        }
        return parse(html)
    }

    private fun post(jpTitle: String): String {
        val t = token ?: parseToken(http.get(HOME)).also { token = it }
        return http.postForm(SEARCH, mapOf("_token" to t, "searchforbooks_title" to jpTitle))
    }
}
