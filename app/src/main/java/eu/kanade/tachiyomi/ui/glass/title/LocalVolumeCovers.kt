package eu.kanade.tachiyomi.ui.glass.title

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * A local volume's own first page as its cover on the title page: found the way [LocalSource] picks a series cover from
 * its first chapter (`updateCover`: first image by natural name order), downscaled once into
 * `cacheDir/glass_covers/<mangaId>_<chapterId>_<stamp>.jpg`; stamp = the file's size and mtime, so a replaced file
 * extracts again.
 */
object LocalVolumeCovers {

    private const val LONG_SIDE = 512

    private val app: Application by lazy { Injekt.get<Application>() }
    private val dir by lazy { File(app.cacheDir, "glass_covers").apply { mkdirs() } }
    private val permits = Semaphore(2)

    /** Each of [chapters] (volume number → chapter) whose cover is ready, as it completes; failures are skipped. */
    suspend fun load(mangaId: Long, chapters: Map<Int, Chapter>, onCover: (Int, File) -> Unit) = withIOContext {
        val source = Injekt.get<SourceManager>().get(LocalSource.ID) as? LocalSource ?: return@withIOContext
        coroutineScope {
            chapters.map { (number, chapter) ->
                async {
                    permits.withPermit { cover(source, mangaId, chapter) }?.let { onCover(number, it) }
                }
            }.awaitAll()
        }
    }

    private fun cover(source: LocalSource, mangaId: Long, chapter: Chapter): File? = try {
        val format = source.getFormat(chapter.toSChapter())
        val file = when (format) {
            is Format.Directory -> format.file
            is Format.Archive -> format.file
            is Format.Epub -> format.file
        }
        val prefix = "${mangaId}_${chapter.id}_"
        val out = File(dir, "$prefix${file.length()}-${file.lastModified()}.jpg")
        if (out.exists()) {
            out
        } else {
            dir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() } // an older copy of this volume
            firstImage(format)?.let { bytes -> write(bytes, out) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logcat(LogPriority.WARN, e) { "Volume cover failed: ${chapter.url}" }
        null
    }

    /** The first page's bytes; the archive is closed before returning. */
    private fun firstImage(format: Format): ByteArray? = when (format) {
        is Format.Directory -> format.file.listFiles()
            ?.sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
            ?.find { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            ?.openInputStream()?.use { it.readBytes() }
        is Format.Archive -> format.file.archiveReader(app).use { reader ->
            val entry = reader.useEntries { entries ->
                entries
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                    .find { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
            }
            entry?.let { e -> reader.getInputStream(e.name)?.use { it.readBytes() } }
        }
        is Format.Epub -> format.file.epubReader(app).use { epub ->
            epub.getImagesFromPages().firstOrNull()?.let { name -> epub.getInputStream(name)?.use { it.readBytes() } }
        }
    }

    /** Decoded at a power-of-two sample near [LONG_SIDE], scaled to it, saved as JPEG (tmp + rename). */
    private fun write(bytes: ByteArray, out: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        var sample = 1
        while (long / (sample * 2) >= LONG_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scale = LONG_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            val w = (decoded.width * scale).toInt().coerceAtLeast(1)
            val h = (decoded.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, w, h, true)
        } else {
            decoded
        }
        val tmp = File(out.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        return out.takeIf { tmp.renameTo(it) }
    }
}
