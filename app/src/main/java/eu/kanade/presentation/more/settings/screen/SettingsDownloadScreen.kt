package eu.kanade.presentation.more.settings.screen

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.coverage.Coverage
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import eu.kanade.tachiyomi.data.coverage.SourceCoverage
import eu.kanade.tachiyomi.data.download.RealDebridImportJob
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.ui.browse.source.MangaImportUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsDownloadScreen : SearchableSettings {
    private fun readResolve(): Any = SettingsDownloadScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_downloads

    @Composable
    override fun getPreferences(): List<Preference> {
        val getCategories = remember { Injekt.get<GetCategories>() }
        val allCategories by getCategories.subscribe().collectAsState(initial = emptyList())

        val downloadPreferences = remember { Injekt.get<DownloadPreferences>() }
        val parallelSourceLimit by downloadPreferences.parallelSourceLimit().collectAsState()
        val parallelPageLimit by downloadPreferences.parallelPageLimit().collectAsState()
        // chimahon -->
        val rdToken by downloadPreferences.realDebridToken().collectAsState()
        val rdAccount by produceState<Result<RealDebridImportJob.Account?>?>(null, rdToken) {
            value = runCatching { RealDebridImportJob.account() }
        }
        var showCoverageTest by remember { mutableStateOf(false) }
        if (showCoverageTest) CoverageTestDialog(onDismissRequest = { showCoverageTest = false })
        // chimahon <--
        return listOf(
            Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.realDebridToken(),
                title = "Real-Debrid API token",
                subtitle = "From real-debrid.com/apitoken. Share a magnet or Real-Debrid link to the app to add it to local manga",
            ),
            // chimahon -->
            Preference.PreferenceItem.TextPreference(title = accountText(rdAccount)),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.rd_coverage_test),
                subtitle = stringResource(MR.strings.rd_coverage_test_summary),
                onClick = { showCoverageTest = true },
            ),
            // chimahon <--
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.downloadOnlyOverWifi(),
                title = stringResource(MR.strings.connected_to_wifi),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.saveChaptersAsCBZ(),
                title = stringResource(MR.strings.save_chapter_as_cbz),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.splitTallImages(),
                title = stringResource(MR.strings.split_tall_images),
                subtitle = stringResource(MR.strings.split_tall_images_summary),
            ),
            Preference.PreferenceItem.SliderPreference(
                value = parallelSourceLimit,
                valueRange = 1..10,
                title = stringResource(MR.strings.pref_download_concurrent_sources),
                onValueChanged = { downloadPreferences.parallelSourceLimit().set(it) },
            ),
            Preference.PreferenceItem.SliderPreference(
                value = parallelPageLimit,
                valueRange = 1..15,
                title = stringResource(MR.strings.pref_download_concurrent_pages),
                subtitle = stringResource(MR.strings.pref_download_concurrent_pages_summary),
                onValueChanged = { downloadPreferences.parallelPageLimit().set(it) },
            ),
            getDeleteChaptersGroup(
                downloadPreferences = downloadPreferences,
                categories = allCategories,
            ),
            getAutoDownloadGroup(
                downloadPreferences = downloadPreferences,
                allCategories = allCategories,
            ),
            getDownloadAheadGroup(downloadPreferences = downloadPreferences),
            // KMK -->
            getDownloadCacheRenewInterval(downloadPreferences = downloadPreferences),
            // KMK <--
        )
    }

    @Composable
    private fun getDeleteChaptersGroup(
        downloadPreferences: DownloadPreferences,
        categories: List<Category>,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_delete_chapters),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadPreferences.removeAfterMarkedAsRead(),
                    title = stringResource(MR.strings.pref_remove_after_marked_as_read),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = downloadPreferences.removeAfterReadSlots(),
                    entries = persistentMapOf(
                        -1 to stringResource(MR.strings.disabled),
                        0 to stringResource(MR.strings.last_read_chapter),
                        1 to stringResource(MR.strings.second_to_last),
                        2 to stringResource(MR.strings.third_to_last),
                        3 to stringResource(MR.strings.fourth_to_last),
                        4 to stringResource(MR.strings.fifth_to_last),
                    ),
                    title = stringResource(MR.strings.pref_remove_after_read),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadPreferences.removeBookmarkedChapters(),
                    title = stringResource(MR.strings.pref_remove_bookmarked_chapters),
                ),
                getExcludedCategoriesPreference(
                    downloadPreferences = downloadPreferences,
                    categories = { categories },
                ),
            ),
        )
    }

    @Composable
    private fun getExcludedCategoriesPreference(
        downloadPreferences: DownloadPreferences,
        categories: () -> List<Category>,
    ): Preference.PreferenceItem.MultiSelectListPreference {
        return Preference.PreferenceItem.MultiSelectListPreference(
            preference = downloadPreferences.removeExcludeCategories(),
            entries = categories()
                .associate { it.id.toString() to it.visualName }
                .toImmutableMap(),
            title = stringResource(MR.strings.pref_remove_exclude_categories),
        )
    }

    @Composable
    private fun getAutoDownloadGroup(
        downloadPreferences: DownloadPreferences,
        allCategories: List<Category>,
    ): Preference.PreferenceGroup {
        val downloadNewChaptersPref = downloadPreferences.downloadNewChapters()
        val downloadNewUnreadChaptersOnlyPref = downloadPreferences.downloadNewUnreadChaptersOnly()
        val downloadNewChapterCategoriesPref = downloadPreferences.downloadNewChapterCategories()
        val downloadNewChapterCategoriesExcludePref = downloadPreferences.downloadNewChapterCategoriesExclude()

        val downloadNewChapters by downloadNewChaptersPref.collectAsState()

        val included by downloadNewChapterCategoriesPref.collectAsState()
        val excluded by downloadNewChapterCategoriesExcludePref.collectAsState()
        var showDialog by rememberSaveable { mutableStateOf(false) }
        if (showDialog) {
            TriStateListDialog(
                title = stringResource(MR.strings.categories),
                message = stringResource(MR.strings.pref_download_new_categories_details),
                items = allCategories,
                initialChecked = included.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                initialInversed = excluded.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                itemLabel = { it.visualName },
                onDismissRequest = { showDialog = false },
                onValueChanged = { newIncluded, newExcluded ->
                    downloadNewChapterCategoriesPref.set(newIncluded.fastMap { it.id.toString() }.toSet())
                    downloadNewChapterCategoriesExcludePref.set(newExcluded.fastMap { it.id.toString() }.toSet())
                    showDialog = false
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_auto_download),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadNewChaptersPref,
                    title = stringResource(MR.strings.pref_download_new),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadNewUnreadChaptersOnlyPref,
                    title = stringResource(MR.strings.pref_download_new_unread_chapters_only),
                    enabled = downloadNewChapters,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.categories),
                    subtitle = getCategoriesLabel(
                        allCategories = allCategories,
                        included = included,
                        excluded = excluded,
                    ),
                    enabled = downloadNewChapters,
                    onClick = { showDialog = true },
                ),
            ),
        )
    }

    @Composable
    private fun getDownloadAheadGroup(
        downloadPreferences: DownloadPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.download_ahead),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = downloadPreferences.autoDownloadWhileReading(),
                    entries = listOf(0, 2, 3, 5, 10)
                        .associateWith {
                            if (it == 0) {
                                stringResource(MR.strings.disabled)
                            } else {
                                pluralStringResource(MR.plurals.next_unread_chapters, count = it, it)
                            }
                        }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.auto_download_while_reading),
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.download_ahead_info)),
            ),
        )
    }

    // KMK -->
    @Composable
    private fun getDownloadCacheRenewInterval(
        downloadPreferences: DownloadPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.download_cache_renew_interval),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = downloadPreferences.downloadCacheRenewInterval(),
                    entries = persistentMapOf(
                        -1 to stringResource(KMR.strings.download_cache_renew_interval_manual),
                        1 to stringResource(KMR.strings.download_cache_renew_interval_1hour),
                        2 to stringResource(KMR.strings.download_cache_renew_interval_2hour),
                        6 to stringResource(KMR.strings.download_cache_renew_interval_6hour),
                        12 to stringResource(KMR.strings.download_cache_renew_interval_12hour),
                        24 to stringResource(KMR.strings.download_cache_renew_interval_24hour),
                    ),
                    title = stringResource(KMR.strings.download_cache_renew_interval),
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.download_cache_renew_interval_info)),
            ),
        )
    }
    // KMK <--
}

// chimahon -->
@Composable
private fun accountText(account: Result<RealDebridImportJob.Account?>?): String {
    if (account == null) return stringResource(MR.strings.loading)
    val error = account.exceptionOrNull()
    val a = account.getOrNull()
    return when {
        error != null -> stringResource(MR.strings.rd_account_error, error.message ?: error.javaClass.simpleName)
        a == null -> stringResource(MR.strings.rd_account_no_key)
        a.tokenRejected -> stringResource(MR.strings.rd_account_rejected)
        a.premium -> stringResource(MR.strings.rd_account_premium, a.daysLeft)
        else -> stringResource(MR.strings.rd_account_free)
    }
}

/**
 * Debug surface for the tablet test: an AniList media id or title -> each source's coverage as it answers, a
 * "Fetch vol. 1" through [RealDebridQueue], and that title's live queue rows.
 */
@Composable
private fun CoverageTestDialog(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val library = remember { Injekt.get<AniListLibrary>() }
    val coverage = remember { Injekt.get<SourceCoverage>() }
    val queue = remember { Injekt.get<RealDebridQueue>() }
    val sourceManager = remember { Injekt.get<SourceManager>() }
    val order = remember { coverage.sources().map { it.id } }
    var input by rememberSaveable { mutableStateOf("") }
    var entry by remember { mutableStateOf<AniListLibrary.Entry?>(null) }
    var notInList by remember { mutableStateOf(false) }
    var run by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<Coverage>() }
    val rows by remember { queue.rows }.collectAsState(initial = emptyList())
    val noVolume = stringResource(MR.strings.rd_coverage_no_volume)

    LaunchedEffect(run) {
        results.clear()
        val e = entry ?: return@LaunchedEffect
        coverage.forTitle(e, force).collect { c ->
            results.removeAll { it.sourceId == c.sourceId }
            results.add(c)
        }
    }

    fun check(refetch: Boolean) {
        val text = input.trim()
        val key = MangaImportUtil.seriesKey(text)
        entry = text.toLongOrNull()?.let { library.get(it) }
            ?: library.entries.value.takeIf { key.isNotEmpty() }
                ?.firstOrNull { e -> e.titles.any { MangaImportUtil.seriesKey(it) == key } }
        notInList = entry == null
        force = refetch
        run++
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.rd_coverage_test)) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(text = stringResource(MR.strings.rd_coverage_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { check(refetch = false) }) {
                        Text(text = stringResource(MR.strings.rd_coverage_check))
                    }
                    TextButton(onClick = { check(refetch = true) }) {
                        Text(text = stringResource(MR.strings.rd_coverage_refetch))
                    }
                }
                if (notInList) Text(text = stringResource(MR.strings.rd_coverage_not_in_list))
                entry?.let { Text(text = it.native ?: it.romaji ?: it.mediaId.toString(), style = MaterialTheme.typography.titleSmall) }
                val inOrder = CoverageMatch.ordered(results, order)
                val titleRows = rows.filter { it.mediaId != null && it.mediaId == entry?.mediaId }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(inOrder) { c ->
                        Text(
                            text = coverageLine(c),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(titleRows) { row ->
                        val source = (row.servedBy ?: row.sourceId)?.let { sourceManager.get(it)?.name }.orEmpty()
                        Text(
                            text = stringResource(
                                MR.strings.rd_row,
                                CoverageMatch.ranges(row.volumes),
                                source,
                                rowState(row),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = entry != null && results.isNotEmpty(),
                onClick = {
                    entry?.let { e ->
                        if (queue.enqueue(e, 1, CoverageMatch.ordered(results, order)).isEmpty()) context.toast(noVolume)
                    }
                },
            ) {
                Text(text = stringResource(MR.strings.rd_coverage_fetch))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_close))
            }
        },
    )
}

@Composable
private fun coverageLine(c: Coverage): String = when (c.state) {
    Coverage.State.FOUND -> stringResource(
        MR.strings.rd_coverage_found,
        c.sourceName,
        CoverageMatch.ranges(c.volumes.keys).ifEmpty { "–" },
        c.postTitle.orEmpty(),
    )
    Coverage.State.NOT_FOUND -> stringResource(MR.strings.rd_coverage_not_found, c.sourceName)
    Coverage.State.NEEDS_VISIT -> stringResource(MR.strings.rd_coverage_needs_visit, c.sourceName)
    Coverage.State.ERROR -> stringResource(MR.strings.rd_coverage_error, c.sourceName, c.message.orEmpty())
}

@Composable
private fun rowState(row: RealDebridQueue.Row): String {
    val context = LocalContext.current
    return when (row.state) {
        RealDebridQueue.State.QUEUED -> stringResource(MR.strings.rd_row_queued)
        RealDebridQueue.State.DONE -> stringResource(MR.strings.rd_row_done)
        RealDebridQueue.State.FAILED -> stringResource(MR.strings.rd_row_failed, row.reason.orEmpty())
        RealDebridQueue.State.RUNNING -> {
            val step = when (row.step) {
                RealDebridImportJob.STEP_DOWNLOADING -> stringResource(MR.strings.rd_step_downloading)
                RealDebridImportJob.STEP_UNPACKING -> stringResource(MR.strings.rd_step_unpacking)
                RealDebridImportJob.STEP_ADDING -> stringResource(MR.strings.rd_step_adding)
                else -> stringResource(MR.strings.rd_step_waiting)
            }
            when {
                // waiting: bytes = Real-Debrid's torrent percent of 100
                row.step == RealDebridImportJob.STEP_WAITING && row.total == 100L -> "$step ${row.bytes}%"
                row.step == RealDebridImportJob.STEP_DOWNLOADING && row.total > 0 -> buildString {
                    append(step).append(' ')
                    append(Formatter.formatShortFileSize(context, row.bytes)).append(" / ")
                    append(Formatter.formatShortFileSize(context, row.total))
                    if (row.speed > 0) append(" · ").append(Formatter.formatShortFileSize(context, row.speed)).append("/s")
                }
                else -> step
            }
        }
    }
}
// chimahon <--
