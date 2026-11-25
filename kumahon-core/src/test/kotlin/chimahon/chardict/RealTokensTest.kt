package chimahon.chardict

import chimahon.lang.JapaneseModel
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The real splitter's tokens for the face sentences, printed to stdout; runs only with KUMAHON_SUDACHI_DIR set (see JapaneseModelTest). */
class RealTokensTest {
    @Test fun dump() {
        val m = System.getenv("KUMAHON_SUDACHI_DIR")?.let(::File)?.let { JapaneseModel(it) }?.takeIf { it.isReady }
        assumeTrue(m != null)
        for (t in listOf("千尋さんビール飲みたいって言ってましたよね", "千尋さん ビール飲みたいって 言ってましたよね", "山口", "山口さん")) {
            println(t + "\n" + m!!.words(t)!!.joinToString("\n") { "  ${it.surface}/${it.base}/${it.begin}/${it.pos}/${it.sub}/word=${it.isWord}" })
        }
    }
}
