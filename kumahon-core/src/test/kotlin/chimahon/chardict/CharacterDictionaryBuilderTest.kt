package chimahon.chardict

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/** The whole flow with a fake AniList and fake downloads: titles/ids to a zip on disk. */
class CharacterDictionaryBuilderTest {
    private fun fixture(name: String) = CharacterDictionaryBuilderTest::class.java.getResource("/chimahon/chardict/$name")!!.readText()

    @Test
    fun anUnclosedAniListSpoilerHidesTheRest() {
        assertEquals("A cook.", ContentBuilder.stripSpoilers("A cook. ~!He wins!~"))
        assertEquals("A cook.", ContentBuilder.stripSpoilers("A cook. ~!He wins and\nmore"))
    }

    private fun png(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, if (x < w / 2) 0xFFCC3333.toInt() else 0x8033CC33.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    /** AniList with Shokugeki (two character pages) as MANGA 20954 and ANIME 21; media 999 is not found. */
    private class FakeAniList(val pages: List<String>) : AniListTransport {
        val queries = ArrayList<JSONObject>()
        override fun post(body: String): AniListResponse {
            val j = JSONObject(body)
            queries.add(j)
            val q = j.getString("query")
            val v = j.getJSONObject("variables")
            return when {
                "characters(" in q -> {
                    if (v.getInt("id") == 999) return AniListResponse(404, null, """{"errors":[{"message":"Not Found."}]}""")
                    AniListResponse(200, null, pages[v.getInt("page") - 1])
                }
                "relations" in q -> AniListResponse(200, null, """{"data":{"Media":{"relations":{"edges":[]}}}}""")
                else -> AniListResponse(
                    200, null,
                    if (v.getString("t") == "MANGA" && v.getString("s").lowercase().contains("shokugeki")) """{"data":{"Page":{"media":[{"id":20954,"type":"MANGA","title":{"native":"食戟のソーマ","romaji":"Shokugeki no Soma"}}]}}}"""
                    else """{"data":{"Page":{"media":[]}}}""",
                )
            }
        }
    }

    private fun tmp(): File = kotlin.io.path.createTempDirectory("chardict-builder").toFile()

    @Test fun buildsTheZipForATitleWithPortraitsAndFromAlias() {
        val dir = tmp()
        try {
            val api = FakeAniList(listOf(fixture("characters-p1.json"), fixture("characters-p2.json")))
            val downloads = java.util.Collections.synchronizedList(ArrayList<String>()) // two image threads: a plain ArrayList lost an add
            val builder = CharacterDictionaryBuilder(
                images = { url -> downloads.add(url); png(300, 400) },
                resizer = JvmImageResizer,
                anilist = AniListCharacters(api, { }, { 0 }),
                sleep = { },
                imageThreads = 2,
            )
            val out = File(dir, "kumahon_characters.zip")
            val r = builder.buildForTitles(listOf("Shokugeki no Soma 12"), out)
            assertEquals(listOf(MediaRef(20954, "MANGA")), r.media)
            assertEquals(4, r.characters) // 1, 2, 3 (no Japanese name: left out of the zip) and 4
            assertEquals(mapOf("食戟のソーマ (Shokugeki no Soma 12)" to 1), r.skippedNoJapanese)
            assertTrue(r.unresolved.isEmpty() && r.failed.isEmpty())

            // the portraits: each URL once; character 1's and the voice actor's and 2's and 4's
            assertEquals(setOf("https://img.test/c1.png", "https://img.test/va1.png", "https://img.test/c2.png", "https://img.test/c4.png"), downloads.toSet())
            assertEquals(downloads.size, downloads.toSet().size)

            ZipFile(out).use { z ->
                assertEquals(setOf("img/c1.jpg", "img/c1_va.jpg", "img/c2.jpg", "img/c4.jpg"), z.entries().asSequence().map { it.name }.filter { it.startsWith("img/") }.toSet())
                val img = ImageIO.read(z.getInputStream(z.getEntry("img/c1.jpg")))
                assertEquals(150, img.width); assertEquals(200, img.height) // 300x400 into 160x200: scaled by height, JPEG
                val card = JSONArray(String(z.getInputStream(z.getEntry("term_bank_1.json")).readBytes()))
                val soma = (0 until card.length()).map { card.getJSONArray(it) }.first { it.getString(0) == "幸平創真" }
                assertTrue("From: 食戟のソーマ (Shokugeki no Soma 12)" in soma.toString(), "the app's title is in the From: line")
                val px = img.getRGB(10, 100)
                assertTrue(((px shr 16) and 0xFF) > 150, "left half stayed red: ${Integer.toHexString(px)}")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aMediaAniListRefusesIsReportedAndTheRestIsBuilt() {
        val dir = tmp()
        try {
            val api = FakeAniList(listOf(fixture("characters-p1.json"), fixture("characters-p2.json")))
            val builder = CharacterDictionaryBuilder(
                images = { null }, // every download fails: no portraits, still a dictionary
                resizer = JvmImageResizer,
                anilist = AniListCharacters(api, { }, { 0 }), sleep = { },
            )
            val out = File(dir, "x.zip")
            val r = builder.build(listOf(MediaRef(999, "MANGA"), MediaRef(20954, "MANGA"), MediaRef(20954, "MANGA")), out)
            assertEquals(listOf(MediaRef(20954, "MANGA")), r.media) // the duplicate id once
            assertEquals(setOf(MediaRef(999, "MANGA")), r.failed.keys)
            ZipFile(out).use { z -> assertTrue(z.entries().asSequence().none { it.name.startsWith("img/") }) }
            assertThrows(AniListException::class.java) { builder.build(listOf(MediaRef(999, "MANGA")), File(dir, "y.zip")) }
            assertTrue(!File(dir, "y.zip").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun anUnknownTitleIsReportedAndNothingResolvedThrows() {
        val dir = tmp()
        try {
            val api = FakeAniList(listOf(fixture("characters-p1.json"), fixture("characters-p2.json")))
            val builder = CharacterDictionaryBuilder(images = { null }, resizer = JvmImageResizer, anilist = AniListCharacters(api, { }, { 0 }), sleep = { })
            val r = builder.buildForTitles(listOf("Shokugeki no Soma", "Nothing Like It"), File(dir, "z.zip"))
            assertEquals(listOf("Nothing Like It"), r.unresolved)
            val none = builder.buildForTitles(listOf("Nothing Like It"), File(dir, "w.zip")) // nothing resolved: a result, not a throw
            assertTrue(none.resolved.isEmpty())
            assertEquals(listOf("Nothing Like It"), none.notFound)
            assertTrue(!File(dir, "w.zip").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theJvmResizerFitsTheBoxKeepsSmallImagesAndPassesUndecodableBytesThrough() {
        val big = JvmImageResizer.resize(png(320, 200)) // wide: scaled by width
        assertEquals("jpg", big.ext); assertEquals(160, big.width); assertEquals(100, big.height)
        val small = JvmImageResizer.resize(png(50, 60))
        assertEquals(50, small.width); assertEquals(60, small.height) // never enlarged
        assertEquals(0xFF.toByte(), small.bytes[0]); assertEquals(0xD8.toByte(), small.bytes[1]) // JPEG
        val junk = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50) // "RIFF....WEBP": ImageIO can't read it
        val r = JvmImageResizer.resize(junk)
        assertEquals("webp", r.ext); assertEquals(0, r.width); assertTrue(r.bytes.contentEquals(junk))
        assertEquals("png", ImageSizes.detectExtension(png(2, 2)))
        assertEquals("c12.jpg", ImageSizes.fileName("12", "jpg"))
    }
}
