package eu.kanade.tachiyomi.data.track.anilist

import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.models.anime.AnimeTrack
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableAnimeTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.anilist.dto.ALOAuth
import eu.kanade.tachiyomi.data.track.model.AnimeTrackSearch
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import exh.log.xLogW
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.Json
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.injectLazy
import tachiyomi.domain.track.anime.model.AnimeTrack as DomainAnimeTrack
import tachiyomi.domain.track.model.Track as DomainTrack

class Anilist(id: Long) : BaseTracker(id, "AniList"), DeletableTracker, AnimeTracker, DeletableAnimeTracker {

    companion object {
        const val READING = 1L
        const val WATCHING = 11L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 5L
        const val PLAN_TO_WATCH = 15L
        const val REREADING = 6L
        const val REWATCHING = 16L

        const val POINT_100 = "POINT_100"
        const val POINT_10 = "POINT_10"
        const val POINT_10_DECIMAL = "POINT_10_DECIMAL"
        const val POINT_5 = "POINT_5"
        const val POINT_3 = "POINT_3"
    }

    private val json: Json by injectLazy()

    private val interceptor by lazy { AnilistInterceptor(this, getPassword()) }

    private val api by lazy { AnilistApi(client, interceptor) }

    override val supportsReadingDates: Boolean = true

    override val supportsPrivateTracking: Boolean = true

    private val scorePreference = trackPreferences.anilistScoreType()

    init {
        // If the preference is an int from APIv1, logout user to force using APIv2
        try {
            scorePreference.get()
        } catch (e: ClassCastException) {
            logout()
            scorePreference.delete()
        }
    }

    override fun getLogo() = R.drawable.brand_anilist

    override fun getStatusList(): List<Long> {
        return listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ, REREADING)
    }

    override fun getStatusListAnime(): List<Long> {
        return listOf(WATCHING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_WATCH, REWATCHING)
    }

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING -> MR.strings.reading
        PLAN_TO_READ -> MR.strings.plan_to_read
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        DROPPED -> MR.strings.dropped
        REREADING -> MR.strings.repeating
        else -> null
    }

    override fun getStatusForAnime(status: Long): StringResource? = when (status) {
        WATCHING -> MR.strings.watching
        PLAN_TO_WATCH -> MR.strings.plan_to_watch
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        DROPPED -> MR.strings.dropped
        REWATCHING -> MR.strings.rewatching
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getWatchingStatus(): Long = WATCHING

    override fun getRereadingStatus(): Long = REREADING

    override fun getRewatchingStatus(): Long = REWATCHING

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> {
        return when (scorePreference.get()) {
            // 10 point
            POINT_10 -> IntRange(0, 10).map(Int::toString).toImmutableList()
            // 100 point
            POINT_100 -> IntRange(0, 100).map(Int::toString).toImmutableList()
            // 5 stars
            POINT_5 -> IntRange(0, 5).map { "$it ★" }.toImmutableList()
            // Smiley
            POINT_3 -> persistentListOf("-", "😦", "😐", "😊")
            // 10 point decimal
            POINT_10_DECIMAL -> IntRange(0, 100).map { (it / 10f).toString() }.toImmutableList()
            else -> throw Exception("Unknown score type")
        }
    }

    override fun get10PointScore(track: DomainTrack): Double {
        // Score is stored in 100 point format
        return track.score / 10.0
    }

    override fun get10PointScore(track: DomainAnimeTrack): Double {
        // Score is stored in 100 point format
        return track.score / 10.0
    }

    override fun indexToScore(index: Int): Double {
        return when (scorePreference.get()) {
            // 10 point
            POINT_10 -> index * 10.0
            // 100 point
            POINT_100 -> index.toDouble()
            // 5 stars
            POINT_5 -> when (index) {
                0 -> 0.0
                else -> index * 20.0 - 10.0
            }
            // Smiley
            POINT_3 -> when (index) {
                0 -> 0.0
                else -> index * 25.0 + 10.0
            }
            // 10 point decimal
            POINT_10_DECIMAL -> index.toDouble()
            else -> throw Exception("Unknown score type")
        }
    }

    override fun displayScore(track: DomainTrack): String {
        val score = track.score

        return when (scorePreference.get()) {
            POINT_5 -> when (score) {
                0.0 -> "0 ★"
                else -> "${((score + 10) / 20).toInt()} ★"
            }

            POINT_3 -> when {
                score == 0.0 -> "0"
                score <= 35 -> "😦"
                score <= 60 -> "😐"
                else -> "😊"
            }

            else -> track.toApiScore()
        }
    }

    override fun displayScore(track: DomainAnimeTrack): String {
        val score = track.score

        return when (scorePreference.get()) {
            POINT_5 -> when (score) {
                0.0 -> "0 â˜…"
                else -> "${((score + 10) / 20).toInt()} â˜…"
            }

            POINT_3 -> when {
                score == 0.0 -> "0"
                score <= 35 -> "ðŸ˜¦"
                score <= 60 -> "ðŸ˜"
                else -> "ðŸ˜Š"
            }

            else -> track.toApiScore()
        }
    }

    private suspend fun add(track: Track): Track {
        return api.addLibManga(track)
    }

    private suspend fun add(track: AnimeTrack): AnimeTrack {
        return api.addLibAnime(track)
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        // If user was using API v1 fetch library_id
        if (track.library_id == null || track.library_id!! == 0L) {
            val libManga = api.findLibManga(track, getUsername().toInt())
                ?: throw Exception("$track not found on user library")
            track.library_id = libManga.library_id
        }

        if (track.status != COMPLETED) {
            if (didReadChapter) {
                if (track.last_chapter_read.toLong() == track.total_chapters && track.total_chapters > 0) {
                    track.status = COMPLETED
                    track.finished_reading_date = System.currentTimeMillis()
                } else if (track.status != REREADING) {
                    track.status = READING
                    if (track.last_chapter_read == 1.0) {
                        track.started_reading_date = System.currentTimeMillis()
                    }
                }
            }
        }

        // chimahon -->
        if (isLocalVolumes(track)) {
            // setRemoteStatus(COMPLETED) copies the chapter total into last_chapter_read: keep AniList's volumes
            if (track.status == COMPLETED && track.total_chapters > 0 &&
                track.last_chapter_read == track.total_chapters.toDouble()
            ) {
                track.last_chapter_read = (
                    api.findLibMangaWithVolumes(track, getUsername().toInt())
                        ?: throw Exception("$track not found on user library")
                    ).second.toDouble()
            }
            return api.updateLibManga(track, progressVolumes = track.last_chapter_read.toInt())
        }
        // chimahon <--
        return api.updateLibManga(track)
    }

    override suspend fun update(track: AnimeTrack, didWatchEpisode: Boolean): AnimeTrack {
        // If user was using API v1 fetch library_id
        if (track.library_id == null || track.library_id!! == 0L) {
            val libAnime = api.findLibAnime(track, getUsername().toInt())
                ?: throw Exception("$track not found on user library")
            track.library_id = libAnime.library_id
        }

        if (track.status != COMPLETED) {
            if (didWatchEpisode) {
                if (track.last_episode_seen.toLong() == track.total_episodes && track.total_episodes > 0) {
                    track.status = COMPLETED
                    track.finished_watching_date = System.currentTimeMillis()
                } else if (track.status != REWATCHING) {
                    track.status = WATCHING
                    if (track.last_episode_seen == 1.0) {
                        track.started_watching_date = System.currentTimeMillis()
                    }
                }
            }
        }

        return api.updateLibAnime(track)
    }

    override suspend fun delete(track: DomainTrack) {
        if (track.libraryId == null || track.libraryId == 0L) {
            val libManga = api.findLibManga(track.toDbTrack(), getUsername().toInt()) ?: return
            return api.deleteLibManga(track.copy(id = libManga.library_id!!))
        }

        api.deleteLibManga(track)
    }

    override suspend fun delete(track: DomainAnimeTrack) {
        if (track.libraryId == null || track.libraryId == 0L) {
            val libAnime = api.findLibAnime(track.toDbTrack(), getUsername().toInt()) ?: return
            return api.deleteLibAnime(track.copy(id = libAnime.library_id!!))
        }

        api.deleteLibAnime(track)
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        // chimahon -->
        val local = isLocalVolumes(track)
        val remote = if (local) {
            api.findLibMangaWithVolumes(track, getUsername().toInt())
        } else {
            api.findLibManga(track, getUsername().toInt())?.let { it to null }
        }
        val remoteTrack = remote?.first
        // chimahon <--
        return if (remoteTrack != null) {
            track.copyPersonalFrom(remoteTrack, copyRemotePrivate = false)
            remote?.second?.let { track.last_chapter_read = it.toDouble() } // chimahon: local → volumes read
            track.library_id = remoteTrack.library_id

            if (track.status != COMPLETED) {
                val isRereading = track.status == REREADING
                track.status = if (!isRereading && hasReadChapters) READING else track.status
            }

            update(track)
        } else {
            // Set default fields if it's not found in the list
            track.status = if (hasReadChapters) READING else PLAN_TO_READ
            track.score = 0.0
            // chimahon: local → the new entry gets volumes read, chapter progress stays 0
            if (local) api.addLibManga(track, progressVolumes = track.last_chapter_read.toInt()) else add(track)
        }
    }

    override suspend fun bind(track: AnimeTrack, hasSeenEpisodes: Boolean): AnimeTrack {
        val remoteTrack = api.findLibAnime(track, getUsername().toInt())
        return if (remoteTrack != null) {
            track.copyPersonalFrom(remoteTrack, copyRemotePrivate = false)
            track.library_id = remoteTrack.library_id

            if (track.status != COMPLETED) {
                val isRewatching = track.status == REWATCHING
                track.status = if (!isRewatching && hasSeenEpisodes) WATCHING else track.status
            }

            update(track)
        } else {
            // Set default fields if it's not found in the list
            track.status = if (hasSeenEpisodes) WATCHING else PLAN_TO_WATCH
            track.score = 0.0
            add(track)
        }
    }

    override suspend fun search(query: String): List<TrackSearch> {
        return api.search(query)
    }

    override suspend fun searchAnime(query: String): List<AnimeTrackSearch> {
        return api.searchAnime(query)
    }

    override suspend fun refresh(track: Track): Track {
        // chimahon -->
        val (remoteTrack, volumes) = if (isLocalVolumes(track)) {
            api.findLibMangaWithVolumes(track, getUsername().toInt()) ?: throw Exception("Could not find manga")
        } else {
            api.getLibManga(track, getUsername().toInt()) to null
        }
        track.copyPersonalFrom(remoteTrack)
        volumes?.let { track.last_chapter_read = it.toDouble() } // local: volumes read, not AniList's chapter progress
        // chimahon <--
        track.title = remoteTrack.title
        track.total_chapters = remoteTrack.total_chapters
        return track
    }

    override suspend fun refresh(track: AnimeTrack): AnimeTrack {
        val remoteTrack = api.getLibAnime(track, getUsername().toInt())
        track.copyPersonalFrom(remoteTrack)
        track.title = remoteTrack.title
        track.total_episodes = remoteTrack.total_episodes
        return track
    }

    override suspend fun login(username: String, password: String) = login(password)

    suspend fun login(token: String) {
        try {
            val oauth = api.createOAuth(token)
            interceptor.setAuth(oauth)
            val (username, scoreType, name) = api.getCurrentUser()
            scorePreference.set(scoreType)
            trackPreferences.anilistName().set(name) // chimahon
            saveCredentials(username.toString(), oauth.accessToken)
        } catch (e: Throwable) {
            logout()
        }
    }

    override fun logout() {
        super.logout()
        trackPreferences.trackToken(this).delete()
        trackPreferences.anilistName().delete() // chimahon
        interceptor.setAuth(null)
    }

    // chimahon: the viewer's name for the glass setup, fetched once for a login made before the name was stored
    suspend fun viewerName(): String = trackPreferences.anilistName().get().ifBlank {
        api.getCurrentUser().third.also { trackPreferences.anilistName().set(it) }
    }

    override suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        return api.getMangaMetadata(track)
    }

    // SY -->
    override suspend fun searchById(id: String): TrackSearch? {
        return try {
            api.searchById(id)
        } catch (e: Exception) {
            xLogW("Error during searchById '$id': ${e.message}", e)
            null
        }
    }
    // SY <--

    fun saveOAuth(alOAuth: ALOAuth?) {
        trackPreferences.trackToken(this).set(json.encodeToString(alOAuth))
    }

    fun loadOAuth(): ALOAuth? {
        return try {
            json.decodeFromString<ALOAuth>(trackPreferences.trackToken(this).get())
        } catch (e: Exception) {
            null
        }
    }

    // KMK -->
    override fun hasNotStartedReading(status: Long): Boolean = status == PLAN_TO_READ
    // KMK <--

    // chimahon -->
    private val getManga: GetManga by injectLazy()

    /**
     * Local-source manga are one archive per volume: their last_chapter_read is AniList's progressVolumes, and every
     * write sends progressVolumes instead of chapter progress (AniList's chapter count is never touched).
     */
    private suspend fun isLocalVolumes(track: Track) = getManga.await(track.manga_id)?.source == LocalSource.ID

    /** Raw `MediaListCollection` response of the signed-in user's manga list. */
    suspend fun fetchMangaList(): String = api.getMangaListCollection(getUsername().toInt())

    /** The authenticated API, for the glass Discover page's own queries (browse, recommendations, + Home). */
    val discoverApi: AnilistApi get() = api

    /**
     * Local-source manga: a chapter is a volume, so a finished volume sets AniList's progressVolumes (never lowers
     * it) and leaves chapter progress as it is on AniList. Returns the track with last_chapter_read = volumes read.
     */
    // ponytail: never sets COMPLETED (Track has no volume total); finish a series by hand on AniList
    suspend fun updateVolumes(track: Track, volume: Int): Track {
        val (remote, remoteVolumes) = api.findLibMangaWithVolumes(track, getUsername().toInt())
            ?: return track // not on the list (removed on AniList): nothing to update
        track.copyPersonalFrom(remote) // fresh status, score, dates; last_chapter_read = AniList chapter progress
        track.library_id = remote.library_id
        if (volume > remoteVolumes) {
            if (track.status != COMPLETED && track.status != REREADING) {
                if (track.status == PLAN_TO_READ && track.started_reading_date == 0L) {
                    track.started_reading_date = System.currentTimeMillis()
                }
                track.status = READING
            }
            api.updateLibManga(track, progressVolumes = volume)
        }
        track.last_chapter_read = maxOf(volume, remoteVolumes).toDouble()
        return track
    }
    // chimahon <--
}
