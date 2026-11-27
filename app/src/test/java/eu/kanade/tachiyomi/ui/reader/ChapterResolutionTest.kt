package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterResolutionTest {

    private class Ch(val id: Long)

    @Test
    fun `a listed chapter is never copied`() {
        val listed = listOf(Ch(1), Ch(2))
        val cache = java.util.concurrent.ConcurrentHashMap<Long, Ch>()
        resolveCached(2, listed, { it.id }, cache) { error("created") } shouldBe listed[1]
        cache.isEmpty() shouldBe true
    }

    @Test
    fun `an unlisted chapter is created once`() {
        val cache = java.util.concurrent.ConcurrentHashMap<Long, Ch>()
        var made = 0
        val a = resolveCached(9, emptyList(), { it.id }, cache) { made++; Ch(9) }
        val b = resolveCached(9, emptyList(), { it.id }, cache) { made++; Ch(9) }
        (a === b) shouldBe true
        made shouldBe 1
    }
}
