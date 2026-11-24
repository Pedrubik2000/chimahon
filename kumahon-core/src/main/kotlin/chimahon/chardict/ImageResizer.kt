package chimahon.chardict

/** A portrait ready for the zip: [width]/[height] are 0 when the image could not be decoded (the bytes are the original). */
class ResizedImage(val bytes: ByteArray, val ext: String, val width: Int, val height: Int)

/** Shrinks a downloaded portrait to a JPEG thumbnail. Android passes a Bitmap-based one; the desktop app and the tests have a javax.imageio one (not here: R8, AWT). */
fun interface ImageResizer {
    fun resize(bytes: ByteArray): ResizedImage
}

/** Bee's `image_handler.rs` (MIT, see NOTICE.md): the extension from the magic bytes and the thumbnail size. */
object ImageSizes {
    /** Portraits fit 160 x 200 (2x for the popup's 100 px height). */
    const val MAX_WIDTH = 160
    const val MAX_HEIGHT = 200

    fun detectExtension(b: ByteArray): String {
        if (b.size >= 4) {
            fun at(i: Int) = b[i].toInt() and 0xFF
            if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return "jpg"
            if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return "png"
            if (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) return "gif"
            if (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 && b.size >= 12 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50
            ) return "webp"
        }
        return "jpg"
    }

    /** The zip name of a character's image. */
    fun fileName(charId: String, ext: String) = "c$charId.$ext"

    /** Size that fits [w] x [h] in the thumbnail box, never enlarging. */
    fun fit(w: Int, h: Int): Pair<Int, Int> {
        if (w <= MAX_WIDTH && h <= MAX_HEIGHT) return w to h
        val scale = minOf(MAX_WIDTH.toDouble() / w, MAX_HEIGHT.toDouble() / h)
        return maxOf(1, Math.round(w * scale).toInt()) to maxOf(1, Math.round(h * scale).toInt())
    }
}
