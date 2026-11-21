package eu.kanade.tachiyomi.source.online.all

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NyaaTest {

    @Test
    fun `rss items`() {
        val entries = NyaaParser.parseRss(javaClass.getResource("/nyaa_rss.xml")!!.readText())
        entries.size shouldBe 75
        entries.all { Regex("^[0-9a-f]{40}$").matches(it.hash) } shouldBe true
        entries.all { it.url.startsWith("https://nyaa.si/view/") } shouldBe true
        entries[0].title.startsWith("スタジオカバナ") shouldBe true
        entries[0].hash shouldBe "07f2cbff7f561f3506f3a91d20b5356db7497c25"
        entries[0].description shouldBe "420.0 MiB · 22 seeders"
    }
}
