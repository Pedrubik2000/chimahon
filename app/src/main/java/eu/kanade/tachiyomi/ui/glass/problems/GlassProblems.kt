package eu.kanade.tachiyomi.ui.glass.problems

import eu.kanade.tachiyomi.data.download.RealDebridImportJob
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.ui.glass.downloads.GlassDownloads

/** What went wrong; the panel turns it into its title and detail lines (canvas `Err-3`). */
enum class ProblemKind { RD_REJECTED, RD_NOT_PREMIUM, DOWNLOAD_FAILED, ANILIST_OFFLINE, ANILIST_SIGNED_OUT }

/** The panel's pills, first = primary. */
enum class ProblemAction { CHANGE_KEY, HIDE, OPEN_TITLE, TRY_AGAIN, RETRY_ANILIST, OPEN_LIBRARY, SIGN_IN }

/**
 * One card of the Needs attention panel. [id] is stable (what Hide for now remembers); [area] is the rail id that
 * gets the dot. DOWNLOAD_FAILED: [mediaId] the title, [caption] its name, [volumes] the failed ones, [detail] the
 * rows' stored reasons as [GlassDownloads.shownReason] shows them ("Dead link: Rawxt"), null when none says more.
 */
data class Problem(
    val id: String,
    val kind: ProblemKind,
    val area: String,
    val actions: List<ProblemAction>,
    val mediaId: Long? = null,
    val caption: String? = null,
    val volumes: List<Int> = emptyList(),
    val detail: String? = null,
)

object GlassProblems {

    const val RD = "rd"

    /**
     * The current list, key problems first. [account] null = no key, not checked yet or no network (nothing to say).
     * [names] = AniList media id → title name. Shared links (no title) are left out: the Downloads page shows them.
     * [here] = media id → volumes on the tablet ([GlassDownloads.volumesHere]): the Downloads page's failed rule.
     */
    fun build(
        account: RealDebridImportJob.Account?,
        rows: List<RealDebridQueue.Row>,
        here: Map<Long, Set<Int>>,
        names: Map<Long, String>,
        signedIn: Boolean,
        aniListError: String?,
        hidden: Set<String>,
    ): List<Problem> = buildList {
        val key = listOf(ProblemAction.CHANGE_KEY, ProblemAction.HIDE)
        when {
            account == null -> Unit
            account.tokenRejected -> add(Problem(RD, ProblemKind.RD_REJECTED, "downloads", key))
            !account.premium -> add(Problem(RD, ProblemKind.RD_NOT_PREMIUM, "downloads", key))
        }
        GlassDownloads.group(rows, here).forEach { g ->
            val id = g.mediaId ?: return@forEach
            val failed = g.failed
            if (failed.isEmpty()) return@forEach
            add(
                Problem(
                    id = "dl:$id",
                    kind = ProblemKind.DOWNLOAD_FAILED,
                    area = "downloads",
                    actions = listOf(ProblemAction.OPEN_TITLE, ProblemAction.TRY_AGAIN),
                    mediaId = id,
                    caption = names[id],
                    volumes = g.failedVolumes,
                    detail = failed.map { GlassDownloads.shownReason(it.reason) }.filter { it.isNotEmpty() }.distinct()
                        .joinToString(" · ").ifEmpty { null },
                ),
            )
        }
        // signed out wins: refresh() then fails with "Not logged in" too
        when {
            !signedIn -> add(Problem("anilist", ProblemKind.ANILIST_SIGNED_OUT, "home", listOf(ProblemAction.SIGN_IN)))
            aniListError != null -> add(
                Problem(
                    "anilist",
                    ProblemKind.ANILIST_OFFLINE,
                    "discover",
                    listOf(ProblemAction.RETRY_ANILIST, ProblemAction.OPEN_LIBRARY),
                    detail = aniListError,
                ),
            )
        }
    }.filterNot { it.id in hidden }

    /** The rail ids that get a dot. */
    fun areas(problems: List<Problem>): Set<String> = problems.mapTo(mutableSetOf()) { it.area }
}
