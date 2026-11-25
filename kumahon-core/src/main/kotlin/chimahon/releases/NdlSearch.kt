package chimahon.releases

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URLEncoder
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory

/**
 * NDL Search OpenSearch (official XML, no key). Carries some JPRO records with an exact `dcterms:issued` (2027-01-04) and NDL's own
 * records weeks after release with a month only (`2026.11`: [Listing.date] null) and the number in `dcndl:volume` ([Listing.vol]).
 */
class NdlSearch(private val http: ReleaseHttp) : ReleaseSource {
    companion object {
        const val ID = "ndl"
        private val EXACT = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

        /**
         * The OpenSearch endpoint has no sort and ranks by relevance (a long series: 1416 hits, the next volume not in the first 100),
         * so the next volume number goes into the query: `title=<jpTitle> <lastKnownVol+1>`. Unknown last volume: the bare title.
         */
        fun url(jpTitle: String, lastKnownVol: Int): String {
            val q = if (lastKnownVol > 0) "$jpTitle ${lastKnownVol + 1}" else jpTitle
            return "https://ndlsearch.ndl.go.jp/api/opensearch?title=" + URLEncoder.encode(q, "UTF-8") + "&cnt=100"
        }

        /** Pure: every `<item>` of one answer. */
        fun parse(xml: String): List<Listing> {
            val f = DocumentBuilderFactory.newInstance()
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } // not on every parser (Android's)
            val doc = try {
                f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            } catch (e: Exception) {
                throw ReleaseParseException("ndl: not XML (${e.message})")
            }
            val items = doc.getElementsByTagName("item")
            return List(items.length) { items.item(it) as Element }.mapNotNull { item ->
                fun texts(tag: String) = item.getElementsByTagName(tag).let { n -> List(n.length) { n.item(it).textContent.trim() } }
                val title = (texts("dc:title").firstOrNull() ?: texts("title").firstOrNull()).orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                Listing(
                    title = title,
                    vol = texts("dcndl:volume").firstOrNull()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() },
                    date = texts("dcterms:issued").firstOrNull()?.let { s -> EXACT.find(s)?.let { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) } },
                    publisher = texts("dc:publisher").firstOrNull()?.ifEmpty { null },
                    ebook = texts("category").any { it.contains("電子") },
                    source = ID,
                )
            }
        }
    }

    override val id = ID

    override fun listings(jpTitle: String, today: LocalDate, lastKnownVol: Int): List<Listing> = parse(http.get(url(jpTitle, lastKnownVol)))
}
