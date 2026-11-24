package chimahon.chardict

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One answer of AniList's GraphQL endpoint. */
class AniListResponse(val status: Int, val retryAfter: String?, val body: String)

/** Posts a GraphQL body to AniList; tests pass a fake. */
fun interface AniListTransport {
    @Throws(IOException::class)
    fun post(body: String): AniListResponse
}

/** graphql.anilist.co over HttpURLConnection. AniList answers 403 to a request without a User-Agent. */
class HttpAniListTransport(private val url: String = "https://graphql.anilist.co") : AniListTransport {

    override fun post(body: String): AniListResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "kumahon")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return AniListResponse(status, c.getHeaderField("Retry-After"), text)
        } finally {
            c.disconnect()
        }
    }
}

/** AniList refused for good (or the answer was not what we asked). */
open class AniListException(message: String) : IOException(message)

/** Still 429 after every retry. */
class AniListRateLimited(val attempts: Int, val lastWaitMs: Long) :
    AniListException("AniList rate limited after $attempts attempts (last wait ${lastWaitMs}ms)")

/**
 * AniList for the character dictionary: the characters of a media (Bee's `anilist_client.rs`, MIT, see NOTICE.md),
 * and finding the media of a work title and its franchise (kumapie-server's `characters.py`, `resolve` and `franchise`).
 * Blocking: call it off the main thread. [sleep] is injected so tests don't wait.
 */
class AniListCharacters(
    private val transport: AniListTransport = HttpAniListTransport(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val jitter: () -> Long = { (Math.random() * RETRY_JITTER_MS).toLong() },
) {
    companion object {
        private const val RETRY_JITTER_MS = 500L

        /** Bee's "generation" profile: wait 5 s, 15 s, 45 s, 135 s, then 300 s, or what Retry-After says if that is longer (at most 300 s). */
        private val BACKOFF_MS = longArrayOf(5_000, 15_000, 45_000, 135_000, 300_000, 300_000)
        private const val BACKOFF_CAP_MS = 300_000L
        const val CHARACTERS_PER_PAGE = 25
        const val PAGE_PAUSE_MS = 500L

        private const val SEARCH = "query(\$s:String,\$t:MediaType){Page(perPage:10){media(search:\$s,type:\$t){id type title{native romaji}}}}"
        private const val REL = "query(\$id:Int){Media(id:\$id){relations{edges{relationType node{id type}}}}}"

        /** Bee's CHARACTERS_QUERY, field for field. */
        const val CHARACTERS_QUERY = """
    query (${'$'}id: Int!, ${'$'}type: MediaType, ${'$'}page: Int, ${'$'}perPage: Int) {
        Media(id: ${'$'}id, type: ${'$'}type) {
            id
            title {
                romaji
                english
                native
            }
            characters(page: ${'$'}page, perPage: ${'$'}perPage, sort: [ROLE, RELEVANCE, ID]) {
                pageInfo {
                    hasNextPage
                    currentPage
                }
                edges {
                    role
                    voiceActors(language: JAPANESE) {
                        name {
                            full
                            native
                        }
                        image {
                            large
                        }
                    }
                    node {
                        id
                        name {
                            full
                            native
                            alternative
                            alternativeSpoiler
                            first
                            last
                        }
                        image {
                            large
                        }
                        description
                        gender
                        age
                        dateOfBirth {
                            month
                            day
                        }
                        bloodType
                    }
                }
            }
        }
    }
    """

        private fun str(o: JSONObject?, key: String): String? = if (o == null || !o.has(key) || o.isNull(key)) null else o.get(key) as? String

        private fun strings(o: JSONObject?, key: String): List<String> {
            val a = o?.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> (a.opt(i) as? String)?.takeIf { it.isNotEmpty() } }
        }

        /** One characters edge (role + voice actor + node) as a [Character]; null when it has no node or name. */
        internal fun parseCharacterEdge(edge: JSONObject): Character? {
            val node = edge.optJSONObject("node") ?: return null
            val name = node.optJSONObject("name") ?: return null
            val role = when (str(edge, "role") ?: "BACKGROUND") {
                "MAIN" -> "primary"
                "SUPPORTING" -> "side"
                else -> "appears"
            }
            val sex = when (str(node, "gender")?.lowercase()?.firstOrNull()) { 'm' -> "m"; 'f' -> "f"; else -> null }
            val dob = node.optJSONObject("dateOfBirth")
            val birthday = if (dob != null && !dob.isNull("month") && !dob.isNull("day") && dob.has("month") && dob.has("day")) {
                listOf(dob.optInt("month"), dob.optInt("day"))
            } else null
            val ageValue = if (node.has("age") && !node.isNull("age")) node.get("age") else null
            val age = when (ageValue) { is String -> ageValue; is Number -> ageValue.toLong().toString(); else -> null }
            val va = edge.optJSONArray("voiceActors")?.optJSONObject(0)
            val seiyuu = va?.let { v -> str(v.optJSONObject("name"), "native")?.takeIf { it.isNotEmpty() } ?: str(v.optJSONObject("name"), "full") }
            return Character(
                id = node.optLong("id", 0).toString(),
                name = str(name, "full") ?: "",
                nameOriginal = str(name, "native") ?: "",
                role = role,
                source = "anilist",
                sex = sex,
                age = age,
                bloodType = str(node, "bloodType"),
                birthday = birthday,
                description = str(node, "description"),
                aliases = strings(name, "alternative"),
                spoilerAliases = strings(name, "alternativeSpoiler"),
                imageUrl = str(node.optJSONObject("image"), "large"),
                firstNameHint = str(name, "first")?.trim()?.takeIf { it.isNotEmpty() },
                lastNameHint = str(name, "last")?.trim()?.takeIf { it.isNotEmpty() },
                seiyuu = seiyuu,
                seiyuuImageUrl = va?.let { str(it.optJSONObject("image"), "large") },
            )
        }

        /** The media title the dictionary says "From:": native, else romaji, else english. */
        internal fun mediaTitle(title: JSONObject?): String =
            listOf("native", "romaji", "english").firstNotNullOfOrNull { k -> str(title, k)?.takeIf { it.isNotEmpty() } } ?: ""

        /** The Python side's `_norm`: titles compared without spaces and punctuation, lower case. */
        internal fun norm(s: String?): String = (s ?: "").replace(Regex("[\\s　・〜~!！?？:：\\-]"), "").lowercase()
    }

    /** POSTs one query with the retry policy and returns its "data" object. */
    private fun request(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        var lastWait = 0L
        for (attempt in 0..BACKOFF_MS.size) {
            val r = transport.post(body)
            if (r.status == 429) {
                if (attempt == BACKOFF_MS.size) throw AniListRateLimited(attempt + 1, lastWait)
                val policy = BACKOFF_MS[attempt]
                val retryAfter = r.retryAfter?.trim()?.toLongOrNull()?.times(1000)
                lastWait = minOf(maxOf(retryAfter ?: policy, policy), BACKOFF_CAP_MS) + jitter()
                sleep(lastWait)
                continue
            }
            val json = runCatching { JSONObject(r.body) }.getOrNull()
            val errors = json?.optJSONArray("errors")
            if (r.status == 404 || (errors != null && errors.length() > 0 && (errors.optJSONObject(0)?.optString("message") ?: "").let { "not found" in it || "No such media" in it })) {
                throw AniListException("INVALID_INPUT: AniList media was not found")
            }
            if (r.status != 200) throw AniListException("UPSTREAM: AniList API returned status ${r.status}")
            if (json == null) throw AniListException("UPSTREAM: Failed to parse AniList JSON")
            if (errors != null && errors.length() > 0) throw AniListException("UPSTREAM: AniList GraphQL error: $errors")
            return json.optJSONObject("data") ?: throw AniListException("UPSTREAM: AniList answered without data")
        }
        throw AniListRateLimited(BACKOFF_MS.size + 1, lastWait)
    }

    /** Media matching [text] (a book or manga: type "MANGA"; "ANIME" for anime), best first. */
    fun search(text: String, type: String): List<MediaHit> {
        val data = request(SEARCH, JSONObject().put("s", text).put("t", type))
        val media = data.optJSONObject("Page")?.optJSONArray("media") ?: JSONArray()
        return (0 until media.length()).mapNotNull { i ->
            val m = media.optJSONObject(i) ?: return@mapNotNull null
            val t = m.optJSONObject("title")
            MediaHit(MediaRef(m.optInt("id"), str(m, "type") ?: type), str(t, "native"), str(t, "romaji"))
        }
    }

    private fun hitTitle(m: MediaHit) = norm(m.native?.takeIf { it.isNotEmpty() } ?: m.romaji)

    /**
     * AniList media of a work title ("くま クマ 熊 ベアー17"): the volume number is cut off, the best book/manga match
     * is found and every book, manga or anime whose title starts with its title is added (novel + manga + spin-offs +
     * the anime: AniList puts some characters only on the anime). Empty when AniList doesn't know the title.
     */
    fun resolve(work: String): List<MediaRef> {
        val base = work.trim().replace(Regex("[\\s　]*(第?[0-9０-９]+巻?|vol\\.?\\s*[0-9０-９]+|\\([0-9０-９]+\\))\\s*$", RegexOption.IGNORE_CASE), "")
        for (q in linkedSetOf(base, norm(base), work)) {
            if (q.isEmpty()) continue
            val found = search(q, "MANGA")
            if (found.isNotEmpty()) {
                val top = hitTitle(found[0])
                val same = (found + search(q, "ANIME")).filter { hitTitle(it).startsWith(top) }.map { it.ref }.distinct()
                return same.ifEmpty { listOf(found[0].ref) }
            }
        }
        return emptyList()
    }

    /**
     * The seasons before and after (PREQUEL/SEQUEL, followed on) and the source novel/manga: a sequel's AniList entry
     * lists only a few characters. At most [cap] media (long franchises and AniList's rate limit). Relations are
     * followed from anime only, as the Python does: a manga's own sequels come from [resolve]'s title match.
     */
    fun franchise(media: List<MediaRef>, cap: Int = 15): List<MediaRef> {
        val seen = media.toMutableList()
        val todo = ArrayDeque(media.filter { it.type == "ANIME" })
        while (todo.isNotEmpty() && seen.size < cap) {
            val next = todo.removeFirst()
            val edges = try {
                request(REL, JSONObject().put("id", next.id)).optJSONObject("Media")?.optJSONObject("relations")?.optJSONArray("edges")
            } catch (e: IOException) {
                break // keep what we have: a partial franchise beats none
            } ?: break
            for (i in 0 until edges.length()) {
                val e = edges.optJSONObject(i) ?: continue
                val node = e.optJSONObject("node") ?: continue
                val ref = MediaRef(node.optInt("id"), str(node, "type") ?: continue)
                val rel = str(e, "relationType")
                if (ref in seen || rel !in setOf("PREQUEL", "SEQUEL", "ADAPTATION")) continue
                if (ref.type == "MANGA" || rel != "ADAPTATION") {
                    seen.add(ref)
                    if (ref.type == "ANIME") todo.add(ref)
                }
            }
        }
        return seen.take(cap)
    }

    /** All characters of a media (25 per page, a pause between pages) and the media's title. */
    fun fetchCharacters(media: MediaRef): Pair<String, List<Character>> {
        val out = ArrayList<Character>()
        var title = ""
        var page = 1
        while (true) {
            val data = request(
                CHARACTERS_QUERY,
                JSONObject().put("id", media.id).put("type", media.type.uppercase()).put("page", page).put("perPage", CHARACTERS_PER_PAGE),
            )
            val m = data.optJSONObject("Media") ?: throw AniListException("INVALID_INPUT: AniList media '${media.id}' was not found")
            if (page == 1) title = mediaTitle(m.optJSONObject("title"))
            val chars = m.optJSONObject("characters") ?: throw AniListException("Invalid character response format for AniList media ID ${media.id}")
            val edges = chars.optJSONArray("edges") ?: throw AniListException("Invalid character response format for AniList media ID ${media.id}")
            for (i in 0 until edges.length()) edges.optJSONObject(i)?.let(::parseCharacterEdge)?.let(out::add)
            if (!(chars.optJSONObject("pageInfo")?.optBoolean("hasNextPage", false) ?: false)) break
            page++
            sleep(PAGE_PAUSE_MS)
        }
        return title to out
    }
}
