package chimahon.chardict

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** The character dictionary builder's two Android parts: a Bitmap-based [ImageResizer] and a capped image download (no java.awt / javax.imageio: R8). */
object AndroidImages {
    private const val MAX_BYTES = 2 * 1024 * 1024

    /** Decodes at the biggest power-of-two sample that still covers the thumbnail, scales to [ImageSizes.fit], writes a JPEG on white. An image it can't read comes back as the original with size 0. */
    val resizer = ImageResizer { bytes ->
        val fail = ResizedImage(bytes, ImageSizes.detectExtension(bytes), 0, 0)
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching fail
            val (w, h) = ImageSizes.fit(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching fail
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
            val out = ByteArrayOutputStream()
            flat.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (scaled !== src) scaled.recycle()
            src.recycle()
            flat.recycle()
            ResizedImage(out.toByteArray(), "jpg", w, h)
        }.getOrDefault(fail)
    }

    /** [url]'s bytes, or null on any failure or when it is bigger than 2 MB. Timeouts: 10 s connect, 15 s read. */
    fun download(url: String): ByteArray? = runCatching {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@runCatching null
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "kumahon")
            if (c.responseCode != 200 || c.contentLengthLong > MAX_BYTES) return@runCatching null
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return@runCatching null
                }
            }
            out.toByteArray()
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
