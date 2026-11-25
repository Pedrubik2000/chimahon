package chimahon.releases

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.LocalDate

/** Real requests (3 in all); only with KUMAHON_LIVE set, never in the normal run. Checks the cookie jar + token flow of [JdkReleaseHttp]. */
@EnabledIfEnvironmentVariable(named = "KUMAHON_LIVE", matches = ".+")
class LiveReleasesTest {
    @Test fun booksOrJpAndNdl() {
        val http = JdkReleaseHttp()
        val rows = BooksOrJp(http).listings("ダンダダン", LocalDate.now())
        assertTrue(VolumeMatcher.match("ダンダダン", 25, rows).isNotEmpty(), rows.take(3).toString())
        Thread.sleep(2000)
        assertTrue(NdlSearch(http).listings("ダンダダン", LocalDate.now()).isNotEmpty())
    }
}
