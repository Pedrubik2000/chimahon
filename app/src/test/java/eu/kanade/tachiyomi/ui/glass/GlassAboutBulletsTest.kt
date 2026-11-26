package eu.kanade.tachiyomi.ui.glass

import eu.kanade.tachiyomi.ui.glass.settings.aboutBullets
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlassAboutBulletsTest {

    @Test
    fun `first three bullets, markdown taken out`() {
        val notes = """
            ## What's new
            - **Downloads** tab is the `live` queue
            * Sign in from [Settings](https://example.com) now
            Some paragraph
            -
            - Third
            - Fourth
        """.trimIndent()
        aboutBullets(notes) shouldBe listOf("Downloads tab is the live queue", "Sign in from Settings now", "Third")
    }

    @Test
    fun `older releases after the separator are left out`() {
        val notes = "- New one\r- New two\r-----\r- Old one\r- Old two"
        aboutBullets(notes) shouldBe listOf("New one", "New two")
    }

    @Test
    fun `no bullets`() {
        aboutBullets("Just text") shouldBe emptyList()
    }
}
