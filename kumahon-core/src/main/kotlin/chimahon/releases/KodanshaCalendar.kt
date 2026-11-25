package chimahon.releases

import java.time.LocalDate
import java.time.YearMonth

/**
 * Kodansha's monthly comic calendar `/comic/calendar/YYYYMM` (HTML, up to 3 months ahead, sometimes earlier than JPRO).
 * It lists every Kodansha comic whatever the title: one GET per month, kept for the next series of the same run.
 */
class KodanshaCalendar(private val http: ReleaseHttp, private val monthsAhead: Int = 2) : ReleaseSource {
    companion object {
        const val ID = "kodansha"
        private val ENTRY = Regex(
            """<p[^>]*>\s*(\d{4})年(\d{1,2})月(\d{1,2})日発売\s*</p>\s*<p[^>]*>\s*(.*?)\s*</p>""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** Pure: the entries of one month page (title as printed, e.g. `ブルーロック（４１）`). */
        fun parse(html: String): List<Listing> {
            if ("発売予定表" !in html) throw ReleaseParseException("kodansha: not the calendar page")
            return ENTRY.findAll(html).mapNotNull { m -> runCatching { // a bad date (2月30日) skips the row, not the page
                Listing(
                    title = unescapeHtml(m.groupValues[4]).trim(),
                    vol = null,
                    date = LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()),
                    publisher = "講談社",
                    source = ID,
                )
            }.getOrNull() }.toList()
        }
    }

    override val id = ID
    private val months = HashMap<YearMonth, Result<List<Listing>>>() // a failure is cached too: one failing month is tried once per run, not once per series
    private var cachedDay: LocalDate? = null // the cache is good for one JST day (one run), then the months are fetched again

    @Synchronized
    override fun listings(jpTitle: String, today: LocalDate, lastKnownVol: Int): List<Listing> {
        if (cachedDay != today) { months.clear(); cachedDay = today }
        val from = YearMonth.from(today)
        return (0..monthsAhead).flatMap { i ->
            val ym = from.plusMonths(i.toLong())
            months.getOrPut(ym) {
                try {
                    Result.success(parse(http.get("https://www.kodansha.co.jp/comic/calendar/%04d%02d".format(ym.year, ym.monthValue))))
                } catch (e: HttpStatusException) {
                    if (e.code == 404) Result.success(emptyList()) else Result.failure(e) // a month not published yet
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }.getOrThrow()
        }
    }
}
