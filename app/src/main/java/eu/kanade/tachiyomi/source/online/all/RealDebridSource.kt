package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import eu.kanade.tachiyomi.data.download.RealDebridImportJob
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** A built-in source whose chapter urls are hoster links or magnets, imported through [RealDebridImportJob]. */
interface RealDebridSource {
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
