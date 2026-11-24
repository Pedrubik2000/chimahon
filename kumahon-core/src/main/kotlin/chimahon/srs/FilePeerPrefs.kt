package chimahon.srs

import java.io.File
import java.util.Properties

/** [PeerPrefs] in a properties file (the desktop app's pairing). */
class FilePeerPrefs(private val file: File) : PeerPrefs {
    private val p = Properties().apply { if (file.isFile) file.inputStream().use { load(it.reader(Charsets.UTF_8)) } }

    @Synchronized override fun get(k: String): String? = p.getProperty(k)

    @Synchronized override fun put(k: String, v: String?) {
        if (v == null) p.remove(k) else p.setProperty(k, v)
        file.absoluteFile.parentFile?.mkdirs()
        file.outputStream().use { p.store(it.writer(Charsets.UTF_8), null) }
    }
}
