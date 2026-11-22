package eu.kanade.tachiyomi.data.dictionary

import eu.kanade.tachiyomi.data.dictionary.RecommendedDictionaries.Found
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RecommendedDictionariesTest {

    private val jitendex = RecommendedDictionaries.byId("jitendex")!!
    private val jmnedict = RecommendedDictionaries.byId("jmnedict")!!
    private val kanjidic = RecommendedDictionaries.byId("kanjidic")!!

    @Test
    fun `sizes read as the design shows them`() {
        RecommendedDictionaries.mb(jitendex.bytes) shouldBe "37"
        RecommendedDictionaries.mb(jmnedict.bytes) shouldBe "11"
        RecommendedDictionaries.mb(kanjidic.bytes) shouldBe "0.7"
        RecommendedDictionaries.totalMb(listOf(jitendex, jmnedict)) shouldBe "48"
        RecommendedDictionaries.totalMb(RecommendedDictionaries.all) shouldBe "49"
        RecommendedDictionaries.totalMb(emptyList()) shouldBe "0.0"
    }

    @Test
    fun `names join with and`() {
        RecommendedDictionaries.names(emptyList()) shouldBe ""
        RecommendedDictionaries.names(listOf("Jitendex")) shouldBe "Jitendex"
        RecommendedDictionaries.names(listOf("Jitendex", "JMnedict")) shouldBe "Jitendex and JMnedict"
        RecommendedDictionaries.names(RecommendedDictionaries.all.map { it.name }) shouldBe "Jitendex, JMnedict and KANJIDIC"
    }

    @Test
    fun `installed by downloadUrl, or by folder name in its type`() {
        val found = listOf(
            Found("term", "jitendex-renamed", jitendex.url),
            Found("kanji", "KANJIDIC [2026-326]", null),
            Found("term", "daijirin4-gpt-6-luna es-jp", null),
            Found("frequency", "JPDB", null),
        )
        RecommendedDictionaries.installedIds(found) shouldBe setOf("jitendex", "kanjidic")
        RecommendedDictionaries.others(found) shouldBe listOf("daijirin4-gpt-6-luna es-jp", "JPDB")
        // a name in another type's folder is not it
        RecommendedDictionaries.installedIds(listOf(Found("term", "KANJIDIC copy", null))) shouldBe emptySet()
        // JMnedict is not JMdict
        RecommendedDictionaries.installedIds(listOf(Found("term", "JMdict [2026-11-22]", null))) shouldBe emptySet()
        RecommendedDictionaries.installedIds(listOf(Found("term", "JMnedict [2026-11-22]", jmnedict.url))) shouldBe setOf("jmnedict")
    }

    @Test
    fun `own dictionaries follow the profile order and leave the usual ones out`() {
        val found = listOf(
            Found("term", "Jitendex.org [2026-10-03]", null),
            Found("term", "JMnedict [2026-11-22]", jmnedict.url),
            Found("kanji", "KANJIDIC_english", kanjidic.url),
            Found("term", "sankoku8-gpt-5.6-luna es-jp", null),
            Found("term", "daijirin4-gpt-6-luna es-jp", null),
            Found("frequency", "JPDB", null),
        )
        RecommendedDictionaries.others(found, listOf("daijirin4-gpt-6-luna es-jp", "Jitendex.org [2026-10-03]", "sankoku8-gpt-5.6-luna es-jp")) shouldBe
            listOf("daijirin4-gpt-6-luna es-jp", "sankoku8-gpt-5.6-luna es-jp", "JPDB")
    }

    @Test
    fun `own summary names two and counts the rest`() {
        val names = (1..11).map { "d$it" }
        RecommendedDictionaries.ownSummary(emptyList()) shouldBe null
        RecommendedDictionaries.ownSummary(names.take(1)) shouldBe "d1"
        RecommendedDictionaries.ownSummary(names.take(2)) shouldBe "d1 and d2"
        RecommendedDictionaries.ownSummary(names.take(3)) shouldBe "d1, d2 and 1 more"
        RecommendedDictionaries.ownSummary(names) shouldBe "d1, d2 and 9 more"
    }

    @Test
    fun `every entry has what the screens need`() {
        RecommendedDictionaries.all.map { it.id }.distinct().size shouldBe RecommendedDictionaries.all.size
        RecommendedDictionaries.all.forEach {
            (it.type in setOf("term", "kanji")) shouldBe true
            it.url.startsWith("https://") shouldBe true
            it.attribution.isNotBlank() shouldBe true
        }
    }
}
