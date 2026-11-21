package tachiyomi.domain.download.service

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getLongArray

class DownloadPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun downloadOnlyOverWifi() = preferenceStore.getBoolean(
        "pref_download_only_over_wifi_key",
        true,
    )

    fun realDebridToken() = preferenceStore.getString("real_debrid_token", "")

    /** Real-Debrid source ids in the order to try them; empty / missing ones follow the built-in order. */
    fun realDebridSourceOrder() = preferenceStore.getLongArray("real_debrid_source_order", emptyList())

    /** Real-Debrid source ids switched off for coverage and downloads. */
    fun realDebridSourcesOff() = preferenceStore.getLongArray("real_debrid_sources_off", emptyList())

    /** Title page: a volume the chosen source lacks (or whose link is dead) comes from the next source. */
    fun realDebridFillGaps() = preferenceStore.getBoolean("real_debrid_fill_gaps", true)

    fun saveChaptersAsCBZ() = preferenceStore.getBoolean("save_chapter_as_cbz", true)

    fun splitTallImages() = preferenceStore.getBoolean("split_tall_images", true)

    fun autoDownloadWhileReading() = preferenceStore.getInt("auto_download_while_reading", 0)

    fun removeAfterReadSlots() = preferenceStore.getInt("remove_after_read_slots", -1)

    fun removeAfterMarkedAsRead() = preferenceStore.getBoolean(
        "pref_remove_after_marked_as_read_key",
        false,
    )

    fun removeBookmarkedChapters() = preferenceStore.getBoolean("pref_remove_bookmarked", false)

    fun removeExcludeCategories() = preferenceStore.getStringSet(REMOVE_EXCLUDE_CATEGORIES_PREF_KEY, emptySet())

    fun downloadNewChapters() = preferenceStore.getBoolean("download_new", false)

    fun downloadNewChapterCategories() = preferenceStore.getStringSet(DOWNLOAD_NEW_CATEGORIES_PREF_KEY, emptySet())

    fun downloadNewChapterCategoriesExclude() =
        preferenceStore.getStringSet(DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY, emptySet())

    fun downloadNewUnreadChaptersOnly() = preferenceStore.getBoolean("download_new_unread_chapters_only", false)

    fun numberOfDownloads() = preferenceStore.getInt("download_slots", 1)
    fun downloadSpeedLimit() = preferenceStore.getInt("download_speed_limit", 0)
    fun useExternalDownloader() = preferenceStore.getBoolean("use_external_downloader", false)
    fun externalDownloaderSelection() = preferenceStore.getString("external_downloader_selection", "")
    fun notDownloadFillermarkedItems() = preferenceStore.getBoolean("pref_no_download_fillermarked", false)
    fun downloadFillermarkedItems() = preferenceStore.getBoolean("pref_download_fillermarked", true)

    fun removeExcludeAnimeCategories() = preferenceStore.getStringSet(REMOVE_EXCLUDE_ANIME_CATEGORIES_PREF_KEY, emptySet())

    fun parallelSourceLimit() = preferenceStore.getInt("download_parallel_source_limit", 5)

    fun parallelPageLimit() = preferenceStore.getInt("download_parallel_page_limit", 5)

    // SY -->
    fun includeChapterUrlHash() = preferenceStore.getBoolean("download_include_chapter_url_hash", false)
    // SY <--

    // KMK -->
    fun downloadCacheRenewInterval() = preferenceStore.getInt("download_cache_renew_interval", 1)
    // KMK <--

    companion object {
        private const val REMOVE_EXCLUDE_CATEGORIES_PREF_KEY = "remove_exclude_categories"
        private const val REMOVE_EXCLUDE_ANIME_CATEGORIES_PREF_KEY = "remove_exclude_anime_categories"
        private const val DOWNLOAD_NEW_CATEGORIES_PREF_KEY = "download_new_categories"
        private const val DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY = "download_new_categories_exclude"
        val categoryPreferenceKeys = setOf(
            REMOVE_EXCLUDE_CATEGORIES_PREF_KEY,
            REMOVE_EXCLUDE_ANIME_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY,
        )
    }
}
