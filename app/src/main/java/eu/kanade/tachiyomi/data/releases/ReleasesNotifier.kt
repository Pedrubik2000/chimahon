package eu.kanade.tachiyomi.data.releases

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import chimahon.releases.EventKind
import chimahon.releases.ReleaseEvent
import chimahon.releases.VolState
import chimahon.releases.VolumeRow
import chimahon.releases.WatchStatus
import chimahon.releases.WatchedSeries
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.anilist.AniListLibrary
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** What a pending event becomes. [fetch] = the "Fetch" action (ON_SOURCES of a title that does not auto-fetch). */
enum class NoteKind { OUT_TODAY, ON_SOURCE, MOVED }

data class Note(val event: ReleaseEvent, val kind: NoteKind, val fetch: Boolean) {
    /** Date changes are quiet (own low channel). */
    val quiet get() = kind == NoteKind.MOVED
}

/**
 * [notes] to post; [silent] = events that need no notification and are cleared with the run
 * (the very first run's burst, auto-fetch titles' ON_SOURCES).
 */
data class Plan(val notes: List<Note>, val silent: List<ReleaseEvent>)

/** The decisions of phase 4, pure (unit-tested). */
object ReleasesPlan {
    /** More notes than this in one channel become one InboxStyle summary. */
    const val SUMMARY_OVER = 3

    /**
     * @param seeded false on the very first run ever: its events are the first scan, not news -> none notified, all cleared.
     * @param silenced events that need no note because an auto-fetch is already running for them (a pending attempt); the others get the Fetch button.
     */
    fun plan(events: List<ReleaseEvent>, seeded: Boolean, silenced: (ReleaseEvent) -> Boolean): Plan {
        if (!seeded) return Plan(emptyList(), events)
        val notes = mutableListOf<Note>()
        val silent = mutableListOf<ReleaseEvent>()
        for (e in events) when (e.kind) {
            EventKind.OUT_TODAY -> notes += Note(e, NoteKind.OUT_TODAY, fetch = false)
            EventKind.DATE_CHANGED -> notes += Note(e, NoteKind.MOVED, fetch = false)
            EventKind.ON_SOURCES ->
                if (silenced(e)) silent += e // phase 5: the auto-fetch posts "on your tablet · Read" when its import ends
                else notes += Note(e, NoteKind.ON_SOURCE, fetch = true)
        }
        return Plan(notes, silent)
    }

    /** [pending] without what was [done] (posted or silent): an event whose posting failed stays for the next run. */
    fun remaining(pending: List<ReleaseEvent>, done: Collection<ReleaseEvent>): List<ReleaseEvent> =
        pending.filterNot { it in done }

    /**
     * One channel's notes -> (summary notes, singles). Only OUT_TODAY / MOVED count toward [SUMMARY_OVER]; ON_SOURCE notes
     * are always single (they keep their Fetch button).
     */
    fun split(notes: List<Note>): Pair<List<Note>, List<Note>> {
        val (singles, countable) = notes.partition { it.kind == NoteKind.ON_SOURCE }
        return if (countable.size > SUMMARY_OVER) countable to singles else emptyList<Note>() to notes
    }

    /** The first-run flag may be set only once the store has rows: an empty store means the first scan has not happened yet. */
    fun seedNow(seeded: Boolean, storeHasRows: Boolean): Boolean = seeded || storeHasRows

    /**
     * THE auto-fetch rule (notifier, worker and bell popover all ask here). Plan to read: never. Otherwise the person's explicit
     * choice; none (null): on only while he is on the latest volume, i.e. [WatchList.behind] is 0 for the title's one row (its newest
     * volume): he has everything up to newest-1. No known place ([WatchedSeries.lastKnownVol] 0): off. No row yet: on (nothing to fetch).
     */
    fun autoFetchOn(s: WatchedSeries?, rows: List<VolumeRow> = emptyList()): Boolean {
        if (s == null || s.status == WatchStatus.PLAN) return false
        s.autoFetch?.let { return it }
        if (s.lastKnownVol <= 0) return false
        val newest = rows.filter { it.anilistId == s.anilistId }.maxOfOrNull { it.vol } ?: return true
        return WatchList.behind(s.lastKnownVol, newest) == 0
    }

    /** Stable per volume and kind: a re-post replaces. */
    fun notificationId(e: ReleaseEvent): Int = ("${e.anilistId}:${e.vol}:${e.kind}").hashCode()
}

/** Posts the events a run left in `pendingEvents`, then clears what was handled. */
object ReleasesNotifier {
    const val ACTION_OPEN = "app.kumahon.OPEN_RELEASES"
    const val EXTRA_ROUTE = "route"
    const val ROUTE_RELEASES = "releases"
    /** Optional: an AniList media id; MainActivity opens that title's page over the Releases page. */
    const val EXTRA_MEDIA = "mediaId"
    private const val ID_SUMMARY = -1911
    private const val ID_SUMMARY_DATES = -1912
    private const val PREFS = "releases_notifier"
    private const val KEY_SEEDED = "seeded"

    /** The first run ever only seeds the store: the worker skips auto-fetch until this is true. */
    fun isSeeded(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEDED, false)

    /** Call at the end of a run. Never throws. */
    fun notifyPending(context: Context) {
        try {
            post(context.applicationContext)
        } catch (e: Exception) {
            Log.w(ReleasesWorker.TAG, "notify failed (events stay pending)", e)
        }
    }

    private fun post(context: Context) {
        val store = ReleasesWorker.store(context)
        val state = store.get()
        val pending = state.pendingEvents
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The first run ever finds everything at once: the store's `notified` is then empty before the run, but is not any more
        // when we get here, so the first call is remembered in a flag instead (set after the clearing below, and only with rows).
        val seeded = prefs.getBoolean(KEY_SEEDED, false)
        fun markSeeded() {
            if (!seeded && ReleasesPlan.seedNow(false, store.get().rows.isNotEmpty())) prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        }
        if (pending.isEmpty()) {
            markSeeded()
            return
        }

        val series = state.series.associateBy { it.anilistId }
        // silent only where AutoFetch.run left a pending attempt (it ran before us); anything else, even of an auto title, gets its Fetch note
        val plan = ReleasesPlan.plan(pending, seeded) { e -> state.attempts.any { it.pending && it.anilistId == e.anilistId && it.vol == e.vol } }
        if (!seeded) Log.i(ReleasesWorker.TAG, "first run: ${pending.size} events cleared without notifying")

        val manager = NotificationManagerCompat.from(context)
        val granted = manager.areNotificationsEnabled()
        val done = plan.silent.toMutableList()
        if (plan.notes.isNotEmpty() && !granted) {
            // chosen: drop them (news that waits for a permission is stale when it comes; the Releases screen still shows the rows)
            Log.i(ReleasesWorker.TAG, "no notification permission: ${plan.notes.size} events dropped")
            done += plan.notes.map { it.event }
        } else {
            val library = Injekt.get<AniListLibrary>()
            fun title(id: Long) = library.get(id)?.let { it.native ?: it.romaji ?: it.english } ?: series[id]?.jpTitle ?: "#$id"
            try {
                for (quiet in listOf(false, true)) {
                    val group = plan.notes.filter { it.quiet == quiet }
                    if (group.isEmpty()) continue
                    val (summed, singles) = ReleasesPlan.split(group)
                    if (summed.isNotEmpty()) {
                        manager.notify(if (quiet) ID_SUMMARY_DATES else ID_SUMMARY, summary(context, summed, quiet, ::title))
                        done += summed.map { it.event }
                    }
                    for (n in singles) {
                        manager.notify(ReleasesPlan.notificationId(n.event), single(context, n, title(n.event.anilistId)))
                        done += n.event
                    }
                }
            } catch (e: Exception) {
                Log.w(ReleasesWorker.TAG, "posting stopped, the rest stays pending", e)
            }
        }
        store.update { it.copy(pendingEvents = ReleasesPlan.remaining(it.pendingEvents, done)) }
        markSeeded()
        Log.i(ReleasesWorker.TAG, "notified: ${done.size} of ${pending.size} events handled")
    }

    /** [AutoFetch.jobEnded] calls this when the import of an auto-fetched [vol] is on the tablet: "<title> / Vol. N is on your tablet · Read" (opens the title page). Never throws. */
    fun onTablet(context: Context, mediaId: Long, vol: Int) {
        try {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            val name = Injekt.get<AniListLibrary>().get(mediaId)?.let { it.native ?: it.romaji ?: it.english }
                ?: ReleasesWorker.store(context).get().series.firstOrNull { it.anilistId == mediaId }?.jpTitle ?: "#$mediaId"
            val open = PendingIntent.getActivity(
                context,
                ("title:$mediaId").hashCode(),
                Intent(context, MainActivity::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_ROUTE, ROUTE_RELEASES).putExtra(EXTRA_MEDIA, mediaId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                "$mediaId:$vol:ON_TABLET".hashCode(),
                context.notificationBuilder(Notifications.CHANNEL_RELEASES) {
                    setSmallIcon(R.drawable.ic_book_24dp)
                    setContentTitle(name)
                    setContentText(context.getString(R.string.releases_notif_on_tablet, vol))
                    setContentIntent(open)
                    setAutoCancel(true)
                }.build(),
            )
        } catch (e: Exception) {
            Log.w(ReleasesWorker.TAG, "on-tablet note failed", e)
        }
    }

    private fun channel(quiet: Boolean) = if (quiet) Notifications.CHANNEL_RELEASE_DATES else Notifications.CHANNEL_RELEASES

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_ROUTE, ROUTE_RELEASES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun text(context: Context, n: Note, title: String): String {
        val e = n.event
        return when (n.kind) {
            NoteKind.OUT_TODAY -> context.getString(R.string.releases_notif_out_today, title, e.vol)
            NoteKind.ON_SOURCE -> e.source?.let { context.getString(R.string.releases_notif_on_source, e.vol, it) }
                ?: context.getString(R.string.releases_notif_on_source_unknown, e.vol)
            NoteKind.MOVED -> context.getString(R.string.releases_notif_moved, e.vol, e.newDate?.toString() ?: "?")
        }
    }

    private fun single(context: Context, n: Note, title: String) = context.notificationBuilder(channel(n.quiet)) {
        setSmallIcon(R.drawable.ic_book_24dp)
        // OUT_TODAY's text already names the title; the other two carry it as the notification title
        if (n.kind == NoteKind.OUT_TODAY) setContentTitle(text(context, n, title)) else {
            setContentTitle(title)
            setContentText(text(context, n, title))
        }
        setContentIntent(open(context))
        setAutoCancel(true)
        if (n.quiet) setPriority(NotificationCompat.PRIORITY_LOW)
        if (n.fetch) {
            addAction(
                0,
                context.getString(R.string.releases_notif_fetch),
                ReleaseFetchReceiver.pendingIntent(context, n.event.anilistId, n.event.vol, ReleasesPlan.notificationId(n.event)),
            )
        }
    }.build()

    private fun summary(context: Context, group: List<Note>, quiet: Boolean, title: (Long) -> String) =
        context.notificationBuilder(channel(quiet)) {
            val head = context.getString(R.string.releases_notif_summary, group.size)
            setSmallIcon(R.drawable.ic_book_24dp)
            setContentTitle(head)
            setContentIntent(open(context))
            setAutoCancel(true)
            if (quiet) setPriority(NotificationCompat.PRIORITY_LOW)
            setStyle(
                NotificationCompat.InboxStyle().setBigContentTitle(head).also { box ->
                    group.forEach { n ->
                        val t = title(n.event.anilistId)
                        box.addLine(
                            when (n.kind) {
                                NoteKind.OUT_TODAY -> context.getString(R.string.releases_notif_line_out_today, t, n.event.vol)
                                NoteKind.ON_SOURCE -> context.getString(R.string.releases_notif_line_on_source, t, n.event.vol, n.event.source ?: "?")
                                NoteKind.MOVED -> context.getString(R.string.releases_notif_line_moved, t, n.event.vol, n.event.newDate ?: "?")
                            },
                        )
                    }
                },
            )
        }.build()
}
