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
        post.volumes[0] shouldBe X3dlParser.Volume("27", "https://uploady.io/5a0m3t5cfxen")
        // Uploady wherever a volume has it; 01-14 has RapidGator only
        post.volumes.map { it.label to host(it.link) } shouldBe listOf(
            "27" to "uploady.io", "26" to "uploady.io", "25" to "uploady.io", "22-24" to "uploady.io",
            "15-21 20-21s" to "uploady.io", "01-14 01-04別スキャン" to "rapidgator.net",
        )
        X3dlParser.rapidGator("zq2l1qeeX2WjymSqopSZmdGVZHGWlW5jxpmdxmSYk2icm5WflZaVmGGXa2aUl5hlm2Fl") shouldBe
            "https://rg.to/file/93381d5fa03036bd9a2eb0450a2e5610"

        val rule = Regex("""^https://(uploady\.io/|(rg\.to|rapidgator\.net)/file/[0-9a-f]{32}$)""")
        listOf("post.html", "post2.html", "post3.html").forEach { name ->
            val volumes = X3dlParser.parsePost(fixture(name)).volumes
            println("$name: " + volumes.joinToString { "${it.label} = ${host(it.link)}" })
            volumes.all { rule.containsMatchIn(it.link) } shouldBe true
            volumes.map { it.label }.distinct().size shouldBe volumes.size
        }
    }

    private fun host(link: String) = link.substringAfter("://").substringBefore('/')
}
