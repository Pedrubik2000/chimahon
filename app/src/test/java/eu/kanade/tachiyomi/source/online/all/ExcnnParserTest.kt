package eu.kanade.tachiyomi.source.online.all

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExcnnParserTest {

    private fun fixture(name: String) = javaClass.getResource("/excnn/$name")!!.readText()

    @Test
    fun `list, post and search pages`() {
        val (posts, hasNext) = ExcnnParser.parseList(fixture("list.html"))
        posts.size shouldBe 10
        hasNext shouldBe true
        posts[6].title shouldBe "konyakusha ni Uwaki Sareta Reijo (婚約者に浮気された令嬢は異国の強面盟主に溺愛される)"
        posts[0].cover!!.startsWith("https://m.media-amazon.com/") shouldBe true

        val post = ExcnnParser.parsePost(fixture("post.html"))
        post.title shouldBe "konyakusha ni Uwaki Sareta Reijo (婚約者に浮気された令嬢は異国の強面盟主に溺愛される)"
        post.volumes.map { it.label } shouldBe listOf("01", "02", "01-02b", "03", "04-06")
        post.volumes.last().link shouldBe
            "https://rapidgator.net/file/a864123c9efa853daa2aba5b6610e1ba/excnn20-11-2026a07.rar.html"

        val (found, _) = ExcnnParser.parseList(fixture("search.html"))
        found.size shouldBe 10
        found.all { "/manga-raw/" in it.url } shouldBe true
        ExcnnParser.isPost(found[0].url) shouldBe true
        ExcnnParser.isPost("https://rapidgator.net/file/x/a.rar.html") shouldBe false
    }
}
