package eu.kanade.tachiyomi.data.track.anilist

import eu.kanade.tachiyomi.data.database.models.TrackImpl
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

// chimahon: local volumes must never send AniList chapter progress
class AnilistVolumesTest {

    private val track = TrackImpl().apply {
        title = "t"
        library_id = 7
        last_chapter_read = 47.0
        status = Anilist.READING
    }

    @Test
    fun `non-local sends progress only, as upstream`() {
        val payload = updateMangaPayload(track, null)
        val vars = payload["variables"]!!.jsonObject
        vars["progress"] shouldBe JsonPrimitive(47)
        vars.containsKey("progressVolumes") shouldBe false
        val query = payload["query"].toString()
        query.contains("progress: \$progress") shouldBe true
        query.contains("progressVolumes") shouldBe false
    }

    @Test
    fun `local sends progressVolumes and no progress`() {
        val payload = updateMangaPayload(track, 12)
        val vars = payload["variables"]!!.jsonObject
        vars["progressVolumes"] shouldBe JsonPrimitive(12)
        vars.containsKey("progress") shouldBe false
        val query = payload["query"].toString()
        query.contains("progressVolumes: \$progressVolumes") shouldBe true
        query.contains("\$progress:") shouldBe false
    }
}
