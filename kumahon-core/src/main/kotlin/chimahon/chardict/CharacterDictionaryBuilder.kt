package chimahon.chardict

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** What a build did: which media made it in and which did not, and how many characters the zip has. */
class BuildResult(
    val media: List<MediaRef>,
    val failed: Map<MediaRef, String>,
    val unresolved: List<String>,
    val characters: Int,
    val skippedNoJapanese: Map<String, Int>,
    /** The works that found their AniList media ([buildForTitles] only): the ones a rebuild need not retry. */
    val resolved: List<String> = emptyList(),
    /** The works AniList has no media for (permanent, unlike an [IOException] or a refused media, which a rebuild retries). */
    val notFound: List<String> = emptyList(),
)

/**
 * Builds the character dictionary of some manga/books on the device itself: AniList characters of each media, their
 * portraits (via [images]), and the Yomitan zip kumapie's Hoshidicts reads. The flow of Bee's `main.rs`
 * `generate_dict_from_entries` (MIT, see NOTICE.md) plus what kumapie-server does around it (`characters.py` and
 * `reading_characters.py`): the work titles that resolved to a media are added to the "From: <native title>" line
 * ("From: くま クマ 熊 ベアー (Kuma Kuma Kuma Bear)"), because kumapie shows a face only when the app's title matches it.
 *
 * Blocking, many minutes for a big franchise (AniList's rate limit): run it off the main thread.
 * @param images downloads a URL (null when it fails: the character just has no portrait).
 * @param resizer shrinks a portrait; the desktop app has a javax.imageio one, Android a Bitmap-based one (kumahon-core no AWT here: R8).
 */
class CharacterDictionaryBuilder(
    private val images: (String) -> ByteArray?,
    private val resizer: ImageResizer,
    private val anilist: AniListCharacters = AniListCharacters(),
    private val settings: DictSettings = DictSettings(),
    private val title: String = YomitanCharacterZip.DEFAULT_TITLE,
    private val imageThreads: Int = 6,
    private val mediaPauseMs: Long = 700,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val onProgress: (String) -> Unit = {},
) {
    /** Work titles (a manga folder name, "くま クマ 熊 ベアー17") to AniList, with [followFranchise] their prequels/sequels/source, then [build]. */
    fun buildForTitles(works: List<String>, out: File, followFranchise: Boolean = true): BuildResult {
        val appTitles = LinkedHashMap<MediaRef, MutableList<String>>()
        val unresolved = ArrayList<String>()
        val resolved = ArrayList<String>()
        val notFound = ArrayList<String>()
        for ((n, work) in works.withIndex()) {
            if (n > 0) sleep(mediaPauseMs)
            onProgress("Looking up $work")
            var refs = try { anilist.resolve(work) } catch (e: IOException) { unresolved.add("$work: ${e.message}"); continue }
            if (refs.isEmpty()) { unresolved.add(work); notFound.add(work); continue }
            if (followFranchise) refs = anilist.franchise(refs)
            resolved.add(work)
            for (r in refs) appTitles.getOrPut(r) { ArrayList() }.add(work)
        }
        // nothing to build (every work not found, or its lookup failed): no zip, the caller records the not-found ones
        if (appTitles.isEmpty()) return BuildResult(emptyList(), emptyMap(), unresolved, 0, emptyMap(), emptyList(), notFound)
        val r = build(appTitles.keys.toList(), out, appTitles)
        // a work any of whose media AniList refused is not resolved: it stays out of builtWorks and is retried
        val failedWorks = r.failed.keys.flatMap { appTitles[it].orEmpty() }.toSet()
        return BuildResult(r.media, r.failed, unresolved, r.characters, r.skippedNoJapanese, resolved.filter { it !in failedWorks }, notFound)
    }

    /**
     * The zip of these media at [out]. [appTitles] are the work titles each media was found for ("From:" aliases).
     * Media AniList refuses are left out and named in [BuildResult.failed]; if none works it throws.
     */
    fun build(media: List<MediaRef>, out: File, appTitles: Map<MediaRef, List<String>> = emptyMap()): BuildResult {
        val unique = media.distinct()
        require(unique.isNotEmpty()) { "no media" }
        val fetched = ArrayList<Triple<MediaRef, String, List<Character>>>()
        val failed = LinkedHashMap<MediaRef, String>()
        val portraits = HashMap<String, ResizedImage?>() // by URL: a character in two media is downloaded once
        val pool = Executors.newFixedThreadPool(imageThreads.coerceAtLeast(1))
        try {
            for ((n, ref) in unique.withIndex()) {
                if (n > 0) sleep(mediaPauseMs)
                onProgress("Characters of $ref (${n + 1}/${unique.size})")
                val (mediaTitle, characters) = try {
                    anilist.fetchCharacters(ref)
                } catch (e: IOException) {
                    failed[ref] = e.message ?: e.toString()
                    continue
                }
                fetched.add(Triple(ref, mediaTitle, withPortraits(characters, portraits, pool)))
            }
        } finally {
            pool.shutdownNow() // a download that hung past the timeout is interrupted
        }
        if (fetched.isEmpty()) throw AniListException("AniList refused every media: $failed")

        // work titles that resolved to a media, per displayed title
        val aliasByTitle = HashMap<String, MutableList<String>>()
        for ((ref, mediaTitle, _) in fetched) {
            val apps = appTitles[ref].orEmpty().filter { it != mediaTitle }
            if (apps.isNotEmpty()) aliasByTitle.getOrPut(mediaTitle) { ArrayList() }.addAll(apps.filter { it !in aliasByTitle[mediaTitle].orEmpty() })
        }

        val zip = YomitanCharacterZip(settings, title, "Character names from " + fetched.joinToString(", ") { it.second })
        var count = 0
        for ((_, mediaTitle, characters) in fetched) {
            val shown = aliasByTitle[mediaTitle]?.let { "$mediaTitle (${it.joinToString(" / ")})" } ?: mediaTitle
            for (c in characters) { zip.addCharacter(c, shown); count++ }
        }
        out.parentFile?.mkdirs()
        zip.writeFile(out)
        return BuildResult(fetched.map { it.first }, failed, emptyList(), count, zip.skippedNoJapaneseSummary)
    }

    /** Characters with their resized portrait (and the voice actor's) set; a failed download leaves it null. */
    private fun withPortraits(
        characters: List<Character>, cache: MutableMap<String, ResizedImage?>, pool: java.util.concurrent.ExecutorService,
    ): List<Character> {
        val urls = LinkedHashSet<String>()
        for (c in characters) {
            if (settings.showImage) c.imageUrl?.let(urls::add)
            if (settings.showSeiyuu) c.seiyuuImageUrl?.let(urls::add)
        }
        urls.removeAll(cache.keys)
        val jobs = urls.map { url -> Callable { url to runCatching { images(url)?.let(resizer::resize) }.getOrNull() } }
        for (f in pool.invokeAll(jobs, 10, java.util.concurrent.TimeUnit.MINUTES)) {
            if (f.isCancelled) continue // timed out: no portrait
            val (url, image) = runCatching { f.get() }.getOrNull() ?: continue
            cache[url] = image
        }
        return characters.map { c ->
            val img = if (settings.showImage) c.imageUrl?.let { cache[it] } else null
            val va = if (settings.showSeiyuu) c.seiyuuImageUrl?.let { cache[it] } else null
            c.copy(
                imageBytes = img?.bytes, imageExt = img?.ext,
                imageWidth = img?.width?.takeIf { it > 0 }, imageHeight = img?.height?.takeIf { it > 0 },
                seiyuuImageBytes = va?.bytes, seiyuuImageExt = va?.ext,
                seiyuuImageWidth = va?.width?.takeIf { it > 0 }, seiyuuImageHeight = va?.height?.takeIf { it > 0 },
            )
        }
    }
}
