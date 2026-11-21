package eu.kanade.tachiyomi.source.online.all

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class X3dlParserTest {

    private fun fixture(name: String) = javaClass.getResource("/x3dl/$name")!!.readText()

    @Test
    fun `list and post pages`() {
        val (posts, hasNext) = X3dlParser.parseList(fixture("list.html"))
        posts.size shouldBe 20
        hasNext shouldBe true
        posts.all { it.cover!!.startsWith("https://") } shouldBe true
        posts.none { it.title.endsWith("巻") } shouldBe true
        posts[0].title shouldBe "[クロマツテツロウ] ドラフトキング"

        // the HTML part of a saved post page (its first n?url= link is a redirector, not a hoster: skipped)
        val post = X3dlParser.parsePost(fixture("post.html"))
        post.title shouldBe "[クロマツテツロウ] ドラフトキング"
        post.volumes.size shouldBe 5
        post.volumes.all { it.link.startsWith("https://uploady.io/") } shouldBe true
        post.volumes[0] shouldBe X3dlParser.Volume("27", "https://uploady.io/5a0m3t5cfxen")
        post.volumes.map { it.label } shouldBe listOf("27", "26", "25", "22-24", "15-21 20-21s")
    }
}
