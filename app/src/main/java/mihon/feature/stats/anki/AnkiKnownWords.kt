package mihon.feature.stats.anki

import android.content.Context
import android.content.pm.PackageManager
import chimahon.anki.AnkiDroidBridge
import chimahon.anki.AnkiDroidInventoryProvider
import chimahon.anki.AnkiProfile
import chimahon.ocr.KumapieColours
import chimahon.srs.AnkiKnown
import chimahon.srs.Cards
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.immersion.service.AnkiInventoryProviderException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The kuma3 / AnkiDroid known-words layer on this device: reads the Mined deck(s) of the Mining profiles through the installed
 * build's provider ([AnkiDroidInventoryProvider]) and stores the known words ([AnkiKnown]) in kumahon's own store as a local layer
 * that is never synced ([chimahon.srs.Srs.replaceAnki]).
 */
object AnkiKnownWords {
    enum class State { NOT_INSTALLED, NEEDS_PERMISSION, READY }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("kumahon_anki_known", Context.MODE_PRIVATE)

    /** The build that would be read: "kuma3", "AnkiDroid debug" or "AnkiDroid" (by the order of [AnkiDroidBridge.PACKAGES]). */
    fun name(context: Context): String = when (AnkiDroidBridge(context).pkg) {
        AnkiDroidBridge.PACKAGES[0] -> "kuma3"
        AnkiDroidBridge.PACKAGES[1] -> "AnkiDroid debug"
        else -> "AnkiDroid"
    }

    fun state(context: Context): State {
        val bridge = AnkiDroidBridge(context)
        try {
            context.packageManager.getPackageInfo(bridge.pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return State.NOT_INSTALLED
        }
        return if (bridge.hasPermission()) State.READY else State.NEEDS_PERMISSION
    }

    /** Opt-in for everyone (no surprise colour change): off until the setup tick or the Settings switch turns it on. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("on", false)

    /** Held while the layer is replaced or emptied, so a read that finishes after "off" cannot put its words back. */
    private val lock = Any()

    /** Off removes the words from the colours at once; on reads again (the caller does, [refresh]). */
    suspend fun setEnabled(context: Context, on: Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            prefs(context).edit().putBoolean("on", on).apply()
            if (!on) {
                Cards.store(context).replaceAnki(emptySet(), 0)
                KumapieColours.clear()
            }
        }
    }

    /** What the row shows, all read off the main thread in one go. */
    class Snapshot(val state: State, val enabled: Boolean, val name: String, val last: Long, val count: Int, val days: Int)

    suspend fun snapshot(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val srs = Cards.store(context)
        Snapshot(state(context), enabled(context), name(context), srs.ankiTime(), srs.ankiCount(), days(context))
    }

    /** Known when a card is stable for this many days ([AnkiKnown.DAY_CHOICES]); default [AnkiKnown.KNOWN_DAYS]. Changing it reads again (the caller). */
    fun days(context: Context): Int = prefs(context).getInt("days", AnkiKnown.KNOWN_DAYS.toInt())
    fun setDays(context: Context, days: Int) = prefs(context).edit().putInt("days", days).apply()

    /**
     * Reads every deck the Mining profiles mine into, all notes that have a card there whatever their note type (the word field is the
     * profile's mapped one for the profile's own note type, else [AnkiKnown.wordFieldIndex]), and replaces the layer; answers the number
     * of known words. Throws with a message for the screen when no deck is set, the permission is missing or the provider fails.
     */
    suspend fun refresh(context: Context): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val profiles = Injekt.get<DictionaryPreferences>().profileStore.getProfiles().filter { it.ankiDeck.isNotBlank() }
        if (profiles.isEmpty()) error("Set the Anki deck in Mining first")
        val provider = AnkiDroidInventoryProvider(app)
        val minDays = days(app).toDouble()
        val words = HashSet<String>()
        try {
            for (deck in profiles.map { it.ankiDeck }.distinct()) {
                // note type -> the word field the profiles mapped for it
                val mapped = profiles.filter { it.ankiDeck == deck }.mapNotNull { p -> p.toInventoryConfiguration()?.let { p.ankiModel to it.expressionField } }.toMap()
                val read = provider.loadDeck(deck)
                val notes = read.notes.map {
                    AnkiKnown.Note(it.id, it.values.getOrElse(AnkiKnown.wordFieldIndex(it.fieldNames, it.sortIndex, mapped[it.model])) { "" }, it.tags)
                }
                val known = AnkiKnown.words(notes, read.cards.map { AnkiKnown.Card(it.noteId, it.stabilityDays, it.intervalDays) }, minDays)
                android.util.Log.i(
                    "AnkiKnownWords",
                    "deck \"$deck\": ${read.notes.size} notes, ${read.cards.size} cards, ${known.size} known words (>= ${days(app)} days), " +
                        "note types: ${read.notes.groupingBy { it.model }.eachCount()}",
                )
                words += known
            }
        } catch (e: AnkiInventoryProviderException) {
            error(e.message ?: e.failure.name)
        }
        synchronized(lock) {
            if (!enabled(app)) return@withContext 0 // switched off while reading: drop the result
            Cards.store(app).replaceAnki(words, System.currentTimeMillis())
        }
        KumapieColours.clear()
        words.size
    }

    /** App start: read again in the background when the layer is on, a build is installed with its permission, and the last read is over an hour old. Never throws. */
    fun refreshOnStart(context: Context) {
        val app = context.applicationContext
        Cards.later {
            if (enabled(app) && state(app) == State.READY && System.currentTimeMillis() - Cards.store(app).ankiTime() > 3_600_000L) {
                runCatching { kotlinx.coroutines.runBlocking { refresh(app) } }
                    .onFailure { android.util.Log.w("kumahon", "kuma3 / AnkiDroid known words: $it") }
            }
        }
    }
}
