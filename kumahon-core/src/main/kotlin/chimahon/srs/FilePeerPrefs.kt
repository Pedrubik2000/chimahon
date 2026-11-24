package chimahon.srs

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** [PeerPrefs] in a properties file (the desktop app's pairing). */
class FilePeerPrefs(private val file: File) : PeerPrefs {
    private val p = Properties().apply { if (file.isFile) file.inputStream().use { load(it.reader(Charsets.UTF_8)) } }

    @Synchronized override fun get(k: String): String? = p.getProperty(k)

    @Synchronized override fun put(k: String, v: String?) {
        if (v == null) p.remove(k) else p.setProperty(k, v)
        file.absoluteFile.parentFile?.mkdirs()
        // temp file next to it, then an atomic swap: a crash mid-write cannot lose the pairing secret
        // (an antivirus may hold the target for a moment: retry, then write directly; never throw out of put)
        try {
            val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
            tmp.outputStream().use { p.store(it.writer(Charsets.UTF_8), null) }
            var moved = false
            for (i in 0 until 4) {
                try {
                    Files.move(tmp.toPath(), file.absoluteFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    moved = true
                    break
                } catch (_: Exception) { if (i < 3) Thread.sleep(100) }
            }
            if (!moved) {
                tmp.delete()
                file.outputStream().use { p.store(it.writer(Charsets.UTF_8), null) }
            }
        } catch (_: Exception) { /* the value stays in memory; the next put tries the file again */ }
    }
}
