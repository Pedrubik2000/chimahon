package chimahon.chardict

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

// A copy of desktop's kumahon.desktop.JvmImageResizer for the core tests (core main must not touch java.awt).
/**
 * javax.imageio resizer (JVM only: Android has no javax.imageio, give it a Bitmap-based [ImageResizer]).
 * ImageIO reads JPEG, PNG and GIF; a WebP it can't read comes back as the original bytes with size 0.
 */
object JvmImageResizer : ImageResizer {
    init {
        System.setProperty("java.awt.headless", "true") // no AWT window on the PC screen, ever
    }

    override fun resize(bytes: ByteArray): ResizedImage {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return ResizedImage(bytes, ImageSizes.detectExtension(bytes), 0, 0)
        val (w, h) = ImageSizes.fit(img.width, img.height)
        // JPEG has no alpha: paint on white
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(img.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null)
        } finally {
            g.dispose()
        }
        return runCatching {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.85f
            }
            val buf = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(buf).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(out, null, null), param)
            }
            writer.dispose()
            ResizedImage(buf.toByteArray(), "jpg", w, h)
        }.getOrElse { ResizedImage(bytes, ImageSizes.detectExtension(bytes), img.width, img.height) }
    }
}
