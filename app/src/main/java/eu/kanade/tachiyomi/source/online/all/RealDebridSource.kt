package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import eu.kanade.tachiyomi.data.download.RealDebridImportJob
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** A built-in source whose chapter urls are hoster links or magnets, imported through [RealDebridImportJob]. */
interface RealDebridSource {
    /** One archive of a post: its chapter name, and its links: the chapter's url first, then the same file on other hosters. */
    /** [seeders] / [size]: a torrent's, when the source says them. */
    data class PostFile(val label: String, val links: List<String>, val seeders: Int? = null, val size: String? = null)

    /** A post's archives in chapter order with every hoster link; null = one link per chapter, read the chapters. */
    suspend fun files(post: SManga): List<PostFile>? = null

    companion object {
        /** Opening or downloading such a source's volumes = Real-Debrid imports into local manga; false for others. */
        fun importIfAny(context: Context, source: Source?, manga: Manga?, chapters: List<Chapter>): Boolean {
            if (source !is RealDebridSource || manga == null) return false
            chapters.forEach { RealDebridImportJob.start(context, it.url, manga.title) }
            context.toast("Real-Debrid: downloading to local manga")
            return true
        }
    }
}
