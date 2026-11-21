package eu.kanade.tachiyomi.data.mangadex

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VolumeCoversTest {

    private fun resource(name: String) = javaClass.getResource(name)!!.readText()

    @Test
    fun `manga search keeps the hit whose links al is the AniList id`() {
        val body = resource("/mangadex_manga.json") // Nanako-san (33809), Nichijou (33082), KonoSuba Nichijou (99850)
        VolumeCovers.matchMangaId(body, 33082) shouldBe "f5bf9cc3-d003-47fe-bc76-99774b2a0562"
        VolumeCovers.matchMangaId(body, 99850) shouldBe "03426dd2-63c5-493e-853e-485d7c7dc9c0"
        VolumeCovers.matchMangaId(body, 12345) shouldBe null
    }

    @Test
    fun `links can be null or an empty array`() {
        val body = """{"result":"ok","data":[
            {"id":"a","attributes":{"links":null}},
            {"id":"b","attributes":{"links":[]}},
            {"id":"c","attributes":{"links":{"al":"7"}}}]}"""
        VolumeCovers.matchMangaId(body, 7) shouldBe "c"
        VolumeCovers.matchMangaId(body, 8) shouldBe null
    }

    @Test
    fun `covers by volume prefer the Japanese one`() {
        val page = VolumeCovers.parseCovers(resource("/mangadex_covers.json"))
        page.total shouldBe 11
        page.covers.size shouldBe 11

        val id = "e83c326b-921b-45ff-bc0c-d667bbfe64cc"
        val covers = VolumeCovers.volumeCovers(id, page.covers + VolumeCovers.Cover(null, "ja", "no-volume.jpg"))
        val base = "https://uploads.mangadex.org/covers/$id/"
        covers shouldBe mapOf(
            1.0 to base + "228a6ec1-96db-4310-a487-01bf96d5c0c2.jpg.256.jpg", // ja listed first
            5.0 to base + "08ca6c48-1d1f-4f66-a49f-0247372220d2.jpg.256.jpg", // ja listed last
            9.0 to base + "e6f6da8d-2aa8-4f15-bc8a-40bfd3676bb6.png.256.jpg", // no ja: the first one
            16.1 to base + "e094b6d2-0518-4d4b-b40b-cce68f567995.jpg.256.jpg",
        )
        covers.keys.toList() shouldBe listOf(1.0, 5.0, 9.0, 16.1)
    }
}
