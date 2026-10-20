package chimahon.ocr

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File

class OcrCacheManagerTest {
    @TempDir
    lateinit var tempDir: File

    private val manga = Manga.create().copy(id = 1L, source = 0L)
    private val chapter = Chapter.create().copy(id = 2L, mangaId = 1L)
    private val source = mockk<Source> { every { id } returns 0L }

    /** A local chapter (not downloaded), so its OCR lives in the internal cache file. */
    private fun manager() = OcrCacheManager(
        context = mockk<Context> { every { filesDir } returns tempDir },
        json = Json,
        downloadManager = mockk<DownloadManager> {
            every { isChapterDownloaded(any(), any(), any(), any(), any(), any()) } returns false
        },
        downloadProvider = mockk<DownloadProvider> {
            every { findChapterDir(any(), any(), any(), any(), any()) } returns null
        },
    )

    private fun block(text: String) = OcrTextBlock(0f, 0f, 1f, 1f, listOf(text))

    private suspend fun OcrCacheManager.text(page: Int) = loadOcrBlocks(manga, chapter, source, page)?.single()?.lines

    @Test
    fun `the parsed chapter stays in step with its file`() = runTest {
        val cache = manager()
        cache.saveOcrBlocks(manga, chapter, source, 0, listOf(block("一")), "ja")
        assertEquals(listOf("一"), cache.text(0))
        cache.saveOcrBlocks(manga, chapter, source, 1, listOf(block("二")), "ja")
        assertEquals(listOf("二"), cache.text(1)) // the save updated the memo

        val fresh = manager() // no memo: parses the file
        assertEquals(listOf("一"), fresh.text(0))
        assertEquals(listOf("二"), fresh.text(1))

        cache.deleteOcrForChapter(manga, chapter, source)
        assertNull(cache.text(0)) // gone with the file
    }
}
