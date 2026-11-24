package chimahon.chardict

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** AniList parsing from saved JSON (no network), the retry policy with a fake clock, and `resolve`/`franchise` as characters.py has them. */
class AniListCharactersTest {
    private fun fixture(name: String) = AniListCharactersTest::class.java.getResource("/chimahon/chardict/$name")!!.readText()

    private fun ok(body: String) = AniListResponse(200, null, body)

    /** Edges of the fixture pages, parsed. */
    private fun edges(name: String): List<Character?> {
        val a = JSONObject(fixture(name)).getJSONObject("data").getJSONObject("Media").getJSONObject("characters").getJSONArray("edges")
        return (0 until a.length()).map { AniListCharacters.parseCharacterEdge(a.getJSONObject(it)) }
    }

    @Test fun parsesAMainCharacter() {
        val c = edges("characters-p1.json")[0]!!
        assertEquals("1", c.id)
        assertEquals("Souma Yukihira", c.name); assertEquals("幸平創真", c.nameOriginal)
        assertEquals("primary", c.role) // AniList MAIN
        assertEquals("anilist", c.source)
        assertEquals("m", c.sex); assertEquals("16", c.age); assertEquals("A", c.bloodType); assertEquals(listOf(9, 1), c.birthday)
        assertEquals(listOf("Soma", "Aoki Umi (碧井海)"), c.aliases) // the empty alias is dropped
        assertEquals(listOf("秘密の名前"), c.spoilerAliases)
        assertEquals("Souma", c.firstNameHint); assertEquals("Yukihira", c.lastNameHint)
        assertEquals("https://img.test/c1.png", c.imageUrl)
        assertEquals("松岡禎丞", c.seiyuu); assertEquals("https://img.test/va1.png", c.seiyuuImageUrl)
        assertTrue(c.description!!.contains("~!He wins!~"))
    }

    @Test fun parsesSupportingWithIntegerAgeAndNullBirthday() {
        val c = edges("characters-p1.json")[1]!!
        assertEquals("side", c.role); assertEquals("f", c.sex); assertEquals("17", c.age)
        assertNull(c.birthday); assertNull(c.bloodType); assertNull(c.description); assertNull(c.seiyuu)
    }

    @Test fun anEdgeWithoutNodeIsSkipped() = assertNull(edges("characters-p1.json")[2])

    @Test fun backgroundNullNativeNonBinaryAndUnknownRole() {
        val (bg, weird) = edges("characters-p2.json")
        assertEquals("appears", bg!!.role); assertEquals("", bg.nameOriginal); assertNull(bg.sex); assertNull(bg.age)
        assertNull(bg.firstNameHint); assertNull(bg.imageUrl); assertNull(bg.birthday)
        assertEquals("appears", weird!!.role) // unknown role
        assertEquals("f", weird.sex)
        assertEquals("Only Romaji", weird.seiyuu) // no native: the romanized name
        assertEquals(listOf("Hina (ひな)"), weird.aliases)
    }

    @Test fun fetchCharactersPagesTwentyFiveAtATime() {
        val bodies = ArrayList<JSONObject>()
        val pages = listOf(fixture("characters-p1.json"), fixture("characters-p2.json"))
        val sleeps = ArrayList<Long>()
        val api = AniListCharacters({ body -> JSONObject(body).also(bodies::add); ok(pages[bodies.size - 1]) }, sleeps::add, { 0 })
        val (title, chars) = api.fetchCharacters(MediaRef(20954, "MANGA"))
        assertEquals("食戟のソーマ", title) // native first
        assertEquals(listOf("1", "2", "3", "4"), chars.map { it.id })
        assertEquals(2, bodies.size)
        val v1 = bodies[0].getJSONObject("variables")
        assertEquals(20954, v1.getInt("id")); assertEquals("MANGA", v1.getString("type")); assertEquals(1, v1.getInt("page")); assertEquals(25, v1.getInt("perPage"))
        assertEquals(2, bodies[1].getJSONObject("variables").getInt("page"))
        assertEquals(listOf(AniListCharacters.PAGE_PAUSE_MS), sleeps) // one pause between the two pages
        val q = bodies[0].getString("query")
        for (f in listOf("alternativeSpoiler", "voiceActors(language: JAPANESE)", "dateOfBirth", "bloodType", "hasNextPage", "sort: [ROLE, RELEVANCE, ID]")) assertTrue(f in q, f)
    }

    @Test fun retryAfterIsHonoredAndPolicyIsTheFloor() {
        val sleeps = ArrayList<Long>()
        val answers = ArrayDeque(listOf(
            AniListResponse(429, "7", ""), // 7 s asked, policy 5 s: 7 s
            AniListResponse(429, "1", ""), // 1 s asked, policy 15 s: 15 s
            AniListResponse(429, null, ""), // none asked: policy 45 s
            AniListResponse(429, "100000", ""), // far too long: capped at 300 s
            ok(fixture("characters-p2.json")),
        ))
        val api = AniListCharacters({ answers.removeFirst() }, sleeps::add, { 0 })
        api.fetchCharacters(MediaRef(1, "ANIME"))
        assertEquals(listOf(7_000L, 15_000L, 45_000L, 300_000L), sleeps)
    }

    @Test fun giveUpAfterTheLastRetry() {
        val sleeps = ArrayList<Long>()
        val api = AniListCharacters({ AniListResponse(429, null, "") }, sleeps::add, { 100 })
        val e = assertThrows(AniListRateLimited::class.java) { api.fetchCharacters(MediaRef(1, "ANIME")) }
        assertEquals(7, e.attempts) // the first try and six retries
        assertEquals(listOf(5_100L, 15_100L, 45_100L, 135_100L, 300_100L, 300_100L), sleeps) // jitter added after the cap
    }

    @Test fun notFoundIsReportedNotRetried() {
        var calls = 0
        val api = AniListCharacters({ calls++; AniListResponse(404, null, """{"errors":[{"message":"Not Found."}]}""") }, { }, { 0 })
        assertThrows(AniListException::class.java) { api.fetchCharacters(MediaRef(1, "MANGA")) }
        assertEquals(1, calls)
    }

    @Test fun httpTransportSendsUserAgentKumahonAndReadsRetryAfter() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0) // 127.0.0.1 only
        var seenAgent: String? = null
        server.createContext("/") { ex ->
            seenAgent = ex.requestHeaders.getFirst("User-Agent")
            val body = ex.requestBody.readBytes()
            val limited = String(body).contains("limited")
            if (limited) ex.responseHeaders.add("Retry-After", "42")
            val out = if (limited) "{}" else """{"data":{"ok":true}}"""
            ex.sendResponseHeaders(if (limited) 429 else 200, out.length.toLong())
            ex.responseBody.use { it.write(out.toByteArray()) }
        }
        server.start()
        try {
            val t = HttpAniListTransport("http://127.0.0.1:${server.address.port}/")
            val r = t.post("""{"query":"x"}""")
            assertEquals(200, r.status); assertEquals("kumahon", seenAgent)
            val l = t.post("""{"query":"limited"}""")
            assertEquals(429, l.status); assertEquals("42", l.retryAfter)
        } finally {
            server.stop(0)
        }
    }

    // ----- resolve / franchise (kumapie-server characters.py) -----

    private fun hit(id: Int, type: String, native: String?, romaji: String? = null) =
        """{"id":$id,"type":"$type","title":{"native":${native?.let { "\"$it\"" }},"romaji":${romaji?.let { "\"$it\"" }}}}"""

    private fun search(vararg hits: String) = ok("""{"data":{"Page":{"media":[${hits.joinToString(",")}]}}}""")

    @Test fun resolveCutsTheVolumeAndKeepsTitlesThatStartWithTheBest() {
        val asked = ArrayList<Pair<String, String>>()
        val api = AniListCharacters({ body ->
            val v = JSONObject(body).getJSONObject("variables")
            asked.add(v.getString("s") to v.getString("t"))
            when (v.getString("t")) {
                "MANGA" -> search(hit(97929, "MANGA", "くま クマ 熊 ベアー"), hit(1, "MANGA", "くま クマ 熊 ベアー 〜今日もくまった〜"), hit(2, "MANGA", "別の漫画"))
                else -> search(hit(127550, "ANIME", "くまクマ熊ベアー"), hit(3, "ANIME", "別のアニメ"))
            }
        }, { }, { 0 })
        val refs = api.resolve("くま クマ 熊 ベアー17")
        assertEquals("くま クマ 熊 ベアー" to "MANGA", asked[0]) // "17" cut off
        // the anime's title has no spaces: AniList's norm() match is on the normalized title, so it starts with the normalized best
        assertEquals(listOf(MediaRef(97929, "MANGA"), MediaRef(1, "MANGA"), MediaRef(127550, "ANIME")), refs)
    }

    @Test fun resolveUnknownTitleIsEmptyAfterTryingEachSpelling() {
        val asked = ArrayList<String>()
        val api = AniListCharacters({ body -> asked.add(JSONObject(body).getJSONObject("variables").getString("s")); search() }, { }, { 0 })
        assertEquals(emptyList<MediaRef>(), api.resolve("Okaeri Alice 3"))
        assertEquals(listOf("Okaeri Alice", "okaerialice", "Okaeri Alice 3"), asked) // base, normalized base, the title as given
    }

    @Test fun normDropsSpacesAndPunctuation() {
        assertEquals("くまクマ熊ベアー", AniListCharacters.norm("くま クマ　熊・ベアー"))
        assertEquals("okaerialice", AniListCharacters.norm("Okaeri Alice!"))
    }

    private fun relations(vararg edges: Triple<String, Int, String>) = ok(
        """{"data":{"Media":{"relations":{"edges":[${edges.joinToString(",") { """{"relationType":"${it.first}","node":{"id":${it.second},"type":"${it.third}"}}""" }}]}}}}""",
    )

    @Test fun franchiseFollowsSeasonsAndTakesTheSourceManga() {
        val graph = mapOf(
            100 to listOf(Triple("SEQUEL", 101, "ANIME"), Triple("ADAPTATION", 500, "MANGA"), Triple("SIDE_STORY", 900, "ANIME")),
            101 to listOf(Triple("PREQUEL", 100, "ANIME"), Triple("SEQUEL", 102, "ANIME"), Triple("ADAPTATION", 501, "ANIME")),
            102 to listOf(Triple("PREQUEL", 101, "ANIME")),
        )
        val api = AniListCharacters({ body ->
            relations(*graph.getValue(JSONObject(body).getJSONObject("variables").getInt("id")).toTypedArray())
        }, { }, { 0 })
        // the ADAPTATION to an anime (501) and the SIDE_STORY are not followed; the source manga (500) is kept
        assertEquals(listOf("ANIME:100", "ANIME:101", "MANGA:500", "ANIME:102"), api.franchise(listOf(MediaRef(100, "ANIME"))).map { it.toString() })
    }

    @Test fun franchiseStopsAtTheCapAndKeepsWhatItHasOnAFailure() {
        val api = AniListCharacters({ body ->
            val id = JSONObject(body).getJSONObject("variables").getInt("id")
            relations(Triple("SEQUEL", id + 1, "ANIME"))
        }, { }, { 0 })
        assertEquals(15, api.franchise(listOf(MediaRef(1, "ANIME"))).size)
        var calls = 0
        val flaky = AniListCharacters({ calls++; if (calls == 1) relations(Triple("SEQUEL", 2, "ANIME")) else AniListResponse(500, null, "") }, { }, { 0 })
        assertEquals(listOf("ANIME:1", "ANIME:2"), flaky.franchise(listOf(MediaRef(1, "ANIME"))).map { it.toString() })
    }

    @Test fun emptyTitleIsMissing() {
        assertEquals("Shokugeki", AniListCharacters.mediaTitle(JSONObject("""{"native":"","romaji":"Shokugeki","english":"Food"}""")))
        assertEquals("Food", AniListCharacters.mediaTitle(JSONObject("""{"native":null,"romaji":"","english":"Food"}""")))
    }

    @Test fun mediaRefParsesKumapiesIds() {
        assertEquals(MediaRef(97929, "MANGA"), MediaRef.parse("MANGA:97929"))
        assertEquals(MediaRef(127550, "ANIME"), MediaRef.parse("ANIME:127550"))
        assertEquals(MediaRef(5, "MANGA"), MediaRef.parse("5")) // an old plain id counts as MANGA
    }
}
