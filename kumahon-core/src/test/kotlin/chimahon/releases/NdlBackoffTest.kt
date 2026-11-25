package chimahon.releases

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.SocketTimeoutException
import java.time.LocalDate

class NdlBackoffTest {
    @TempDir lateinit var dir: File

    @Test fun `two throttled answers in a row stop NDL for the run`() {
        val b = NdlBackoff()
        b.failed(HttpStatusException(429, "u"))
        assertFalse(b.tripped)
        b.failed(java.io.InterruptedIOException("call timeout"))
        assertTrue(b.tripped)
    }

    @Test fun `a success or another error resets the streak`() {
        val b = NdlBackoff()
        b.failed(HttpStatusException(429, "u"))
        b.succeeded()
        b.failed(HttpStatusException(429, "u"))
        assertFalse(b.tripped)
        b.failed(ReleaseParseException("x"))
        b.failed(HttpStatusException(429, "u"))
        assertFalse(b.tripped)
    }

    @Test fun `the download source of a row survives the file, old files load without it`() {
        val d = LocalDate.parse("2026-11-01")
        val s = ReleaseState(rows = listOf(VolumeRow(1, 3, d, VolState.ON_SOURCES, "books.or.jp", d, d, fetchSource = "X3DL")))
        val f = File(dir, "a.json")
        ReleaseStore(f).update { s }
        org.junit.jupiter.api.Assertions.assertEquals(s, ReleaseStore(f).get())
        val old = File(dir, "b.json")
        old.writeText(ReleaseStore.toJson(s).also { it.getJSONArray("rows").getJSONObject(0).remove("fetchSource") }.toString())
        org.junit.jupiter.api.Assertions.assertNull(ReleaseStore(old).get().rows.single().fetchSource)
    }
}
