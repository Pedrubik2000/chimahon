package chimahon.anki

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.domain.immersion.model.AnkiInventoryFailure
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.service.AnkiInventoryConfiguration
import tachiyomi.domain.immersion.service.AnkiInventoryProvider
import tachiyomi.domain.immersion.service.AnkiInventoryProviderException
import tachiyomi.domain.immersion.service.AnkiProviderCapability
import tachiyomi.domain.immersion.service.AnkiProviderCard
import tachiyomi.domain.immersion.service.AnkiProviderInventory
import tachiyomi.domain.immersion.service.AnkiProviderNote
import tachiyomi.domain.immersion.service.AnkiProviderQueryMetrics

/**
 * Read-only adapter for AnkiDroid's official flashcards ContentProvider.
 *
 * Card scheduler state has no modification timestamp in the public provider, so
 * maturity refreshes intentionally use an atomic full snapshot.
 */
class AnkiDroidInventoryProvider(
    private val context: Context,
) : AnkiInventoryProvider {

    /** The installed AnkiDroid build [AnkiDroidBridge] picks (kuma3, then the debug build, then stock); asked each time, builds come and go. */
    private fun pkg() = AnkiDroidBridge(context).pkg

    private fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?) =
        providerRetry { context.contentResolver.query(uri, projection, selection, args, order) }

    /** Every build's provider is `<its app id>.flashcards`, its permission `<its app id>.permission.READ_WRITE_DATABASE` (kuma3's manifest). */
    private fun uri(path: String): Uri = Uri.parse("content://${pkg()}.flashcards/$path")

    override suspend fun probe(enabled: Boolean): AnkiProviderCapability = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext unavailable(AnkiInventoryFailure.DISABLED)
        val packageInfo = try {
            context.packageManager.getPackageInfo(pkg(), 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return@withContext unavailable(AnkiInventoryFailure.NOT_INSTALLED)
        }
        if (context.checkSelfPermission("${pkg()}.permission.READ_WRITE_DATABASE") != PackageManager.PERMISSION_GRANTED) {
            return@withContext unavailable(
                AnkiInventoryFailure.PERMISSION_DENIED,
                packageInfo.versionName,
            )
        }
        try {
            query(
                uri("models"),
                arrayOf(MODEL_ID, MODEL_NAME, MODEL_FIELD_NAMES),
                null,
                null,
                null,
            )?.close() ?: return@withContext unavailable(
                AnkiInventoryFailure.UNSUPPORTED_PROVIDER,
                packageInfo.versionName,
            )
        } catch (_: SecurityException) {
            return@withContext unavailable(
                AnkiInventoryFailure.PERMISSION_DENIED,
                packageInfo.versionName,
            )
        } catch (_: Exception) {
            return@withContext unavailable(
                AnkiInventoryFailure.UNSUPPORTED_PROVIDER,
                packageInfo.versionName,
            )
        }
        AnkiProviderCapability(
            state = CapabilityState.AVAILABLE,
            packageVersion = packageInfo.versionName,
            noteModificationTime = true,
            cardModificationTime = false,
            reviewHistory = false,
        )
    }

    override suspend fun load(
        configuration: AnkiInventoryConfiguration,
    ): AnkiProviderInventory = withContext(Dispatchers.IO) {
        val capability = probe(configuration.enabled)
        if (capability.state != CapabilityState.AVAILABLE) {
            throw AnkiInventoryProviderException(
                capability.failure ?: AnkiInventoryFailure.PROVIDER_ERROR,
                "AnkiDroid inventory is unavailable",
            )
        }
        try {
            val model = findModel(configuration.noteTypeName)
                ?: throw AnkiInventoryProviderException(
                    AnkiInventoryFailure.MISCONFIGURED_FIELDS,
                    "Configured AnkiDroid note type does not exist",
                )
            val deckId = findDeckId(configuration.deckName)
                ?: throw AnkiInventoryProviderException(
                    AnkiInventoryFailure.MISCONFIGURED_FIELDS,
                    "Configured AnkiDroid deck does not exist",
                )
            val requiredFields = buildSet {
                add(configuration.expressionField)
                configuration.readingField?.takeIf(String::isNotBlank)?.let(::add)
                configuration.characterField?.takeIf(String::isNotBlank)?.let(::add)
            }
            if (!model.fieldNames.containsAll(requiredFields)) {
                throw AnkiInventoryProviderException(
                    AnkiInventoryFailure.MISCONFIGURED_FIELDS,
                    "Configured AnkiDroid fields do not exist",
                )
            }

            val noteStart = SystemClock.elapsedRealtime()
            val notes = readNotes(model)
            val noteQueryMillis = SystemClock.elapsedRealtime() - noteStart

            val cardStart = SystemClock.elapsedRealtime()
            val cards = readCards(configuration.deckName, configuration.noteTypeName, deckId)
            val cardQueryMillis = SystemClock.elapsedRealtime() - cardStart
            val cardNoteIds = cards.asSequence().map(AnkiProviderCard::noteId).toHashSet()

            AnkiProviderInventory(
                capability = capability,
                notes = notes.filter { it.id in cardNoteIds },
                cards = cards,
                metrics = AnkiProviderQueryMetrics(noteQueryMillis, cardQueryMillis),
            )
        } catch (error: AnkiInventoryProviderException) {
            throw error
        } catch (error: SecurityException) {
            throw AnkiInventoryProviderException(
                AnkiInventoryFailure.PERMISSION_DENIED,
                "AnkiDroid permission was revoked during refresh",
                error,
            )
        } catch (error: UnsupportedOperationException) {
            throw AnkiInventoryProviderException(
                AnkiInventoryFailure.UNSUPPORTED_PROVIDER,
                "Installed AnkiDroid does not expose required scheduler fields",
                error,
            )
        } catch (error: Exception) {
            throw AnkiInventoryProviderException(
                AnkiInventoryFailure.PROVIDER_ERROR,
                "AnkiDroid inventory query failed",
                error,
            )
        }
    }

    /** One note of a deck whatever its note type: its type's name and field names, its field values in the same order, the type's sort field. */
    class DeckNote(val id: Long, val model: String, val fieldNames: List<String>, val values: List<String>, val sortIndex: Int?, val tags: String)

    class DeckRead(val notes: List<DeckNote>, val cards: List<AnkiProviderCard>)

    /**
     * Every note that has a card in [deckName], of any note type, with its cards (FSRS stability when the provider has it). Used by the
     * kuma3 / AnkiDroid known-words layer, which can't know the note types of a deck beforehand.
     */
    suspend fun loadDeck(deckName: String): DeckRead = withContext(Dispatchers.IO) {
        val capability = probe(true)
        if (capability.state != CapabilityState.AVAILABLE) {
            throw AnkiInventoryProviderException(capability.failure ?: AnkiInventoryFailure.PROVIDER_ERROR, "AnkiDroid inventory is unavailable")
        }
        try {
            val deckId = findDeckId(deckName)
                ?: throw AnkiInventoryProviderException(AnkiInventoryFailure.MISCONFIGURED_FIELDS, "Deck \"$deckName\" does not exist")
            val cards = readCards(deckName, null, deckId)
            val cardNoteIds = cards.mapTo(HashSet(), AnkiProviderCard::noteId)
            val notes = ArrayList<DeckNote>()
            for (m in allModels()) {
                for (n in readNotes(Model(m.id, m.fieldNames))) {
                    if (n.id !in cardNoteIds) continue
                    notes += DeckNote(n.id, m.name, m.fieldNames, m.fieldNames.map { f -> n.fields[f].orEmpty() }, m.sortIndex, n.tags)
                }
            }
            DeckRead(notes, cards)
        } catch (error: AnkiInventoryProviderException) {
            throw error
        } catch (error: SecurityException) {
            throw AnkiInventoryProviderException(AnkiInventoryFailure.PERMISSION_DENIED, "AnkiDroid permission was revoked during refresh", error)
        } catch (error: Exception) {
            throw AnkiInventoryProviderException(AnkiInventoryFailure.PROVIDER_ERROR, "AnkiDroid read failed: ${error.message}", error)
        }
    }

    private class DeckModel(val id: Long, val name: String, val fieldNames: List<String>, val sortIndex: Int?)

    private fun allModels(): List<DeckModel> {
        val cursor = try {
            query(uri("models"), arrayOf(MODEL_ID, MODEL_NAME, MODEL_FIELD_NAMES, MODEL_SORT_FIELD_INDEX), null, null, null)
        } catch (e: SecurityException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (_: RuntimeException) { // a provider without the sort field column
            query(uri("models"), arrayOf(MODEL_ID, MODEL_NAME, MODEL_FIELD_NAMES), null, null, null)
        } ?: return emptyList()
        return cursor.use {
            val sortIndex = it.getColumnIndex(MODEL_SORT_FIELD_INDEX)
            buildList {
                while (it.moveToNext()) {
                    add(
                        DeckModel(
                            it.getLong(it.getColumnIndexOrThrow(MODEL_ID)),
                            it.getString(it.getColumnIndexOrThrow(MODEL_NAME)).orEmpty(),
                            parseFieldNames(it.getString(it.getColumnIndexOrThrow(MODEL_FIELD_NAMES)).orEmpty()),
                            if (sortIndex >= 0 && !it.isNull(sortIndex)) it.getInt(sortIndex) else null,
                        ),
                    )
                }
            }
        }
    }

    private fun findModel(name: String): Model? {
        val cursor = query(
            uri("models"),
            arrayOf(MODEL_ID, MODEL_NAME, MODEL_FIELD_NAMES),
            null,
            null,
            null,
        ) ?: return null
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(MODEL_ID)
            val nameIndex = it.getColumnIndexOrThrow(MODEL_NAME)
            val fieldsIndex = it.getColumnIndexOrThrow(MODEL_FIELD_NAMES)
            while (it.moveToNext()) {
                if (it.getString(nameIndex) == name) {
                    return Model(
                        id = it.getLong(idIndex),
                        fieldNames = parseFieldNames(it.getString(fieldsIndex).orEmpty()),
                    )
                }
            }
        }
        return null
    }

    private fun findDeckId(name: String): Long? {
        val cursor = query(
            uri("decks"),
            arrayOf(DECK_ID, DECK_NAME),
            null,
            null,
            null,
        ) ?: return null
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DECK_ID)
            val nameIndex = it.getColumnIndexOrThrow(DECK_NAME)
            while (it.moveToNext()) {
                if (it.getString(nameIndex) == name) return it.getLong(idIndex)
            }
        }
        return null
    }

    private fun readNotes(model: Model): List<AnkiProviderNote> {
        val cursor = query(
            uri("notes_v2"),
            arrayOf(NOTE_ID, NOTE_MID, NOTE_MOD, NOTE_FLDS, NOTE_TAGS),
            "$NOTE_MID = ?",
            arrayOf(model.id.toString()),
            "$NOTE_ID ASC",
        ) ?: throw AnkiInventoryProviderException(
            AnkiInventoryFailure.PARTIAL_RESULT,
            "AnkiDroid note query returned no cursor",
        )
        cursor.requireBounded()
        return cursor.use {
            val result = ArrayList<AnkiProviderNote>(it.count)
            val idIndex = it.getColumnIndexOrThrow(NOTE_ID)
            val midIndex = it.getColumnIndexOrThrow(NOTE_MID)
            val modIndex = it.getColumnIndexOrThrow(NOTE_MOD)
            val fieldsIndex = it.getColumnIndexOrThrow(NOTE_FLDS)
            val tagsIndex = it.getColumnIndexOrThrow(NOTE_TAGS)
            while (it.moveToNext()) {
                val values = it.getString(fieldsIndex).orEmpty().split(FIELD_SEPARATOR)
                result += AnkiProviderNote(
                    id = it.getLong(idIndex),
                    noteTypeId = it.getLong(midIndex),
                    modifiedAtEpochSeconds = it.getLongOrNull(modIndex),
                    tags = it.getString(tagsIndex).orEmpty().trim(),
                    fields = model.fieldNames.mapIndexed { index, field ->
                        field to values.getOrElse(index) { "" }
                    }.toMap(),
                )
            }
            result
        }
    }

    private fun readCards(
        deckName: String,
        noteTypeName: String?,
        deckId: Long,
    ): List<AnkiProviderCard> {
        val browserQuery = buildString {
            append("deck:\"")
            append(deckName.escapeBrowserQuery())
            if (noteTypeName != null) append("\" note:\"")
            if (noteTypeName != null) append(noteTypeName.escapeBrowserQuery())
            append('"')
        }
        // kuma3's provider has the FSRS stability column; stock AnkiDroid throws on an unknown column: then the same query without it.
        val cursor = try {
            query(uri("cards"), CARD_PROJECTION + CARD_STABILITY, browserQuery, null, null)
        } catch (e: SecurityException) {
            throw e
        } catch (e: IllegalStateException) { // the provider is not ready (already retried): not a missing column
            throw e
        } catch (_: RuntimeException) { // an unknown column comes back as UnsupportedOperationException, IllegalArgumentException or SQLiteException (across binder)
            query(uri("cards"), CARD_PROJECTION, browserQuery, null, null)
        } ?: throw AnkiInventoryProviderException(
            AnkiInventoryFailure.PARTIAL_RESULT,
            "AnkiDroid card query returned no cursor",
        )
        cursor.requireBounded()
        return cursor.use {
            val result = ArrayList<AnkiProviderCard>(it.count)
            while (it.moveToNext()) {
                val rowDeckId = it.getLong(it.getColumnIndexOrThrow(CARD_DECK_ID))
                if (rowDeckId != deckId) continue
                result += AnkiProviderCard(
                    id = it.getLong(it.getColumnIndexOrThrow(CARD_ID)),
                    noteId = it.getLong(it.getColumnIndexOrThrow(CARD_NOTE_ID)),
                    deckId = rowDeckId,
                    type = it.getIntOrNull(it.getColumnIndexOrThrow(CARD_TYPE)),
                    queue = it.getIntOrNull(it.getColumnIndexOrThrow(CARD_QUEUE)),
                    intervalDays = it.getIntOrNull(it.getColumnIndexOrThrow(CARD_INTERVAL)),
                    due = it.getLongOrNull(it.getColumnIndexOrThrow(CARD_DUE)),
                    repetitions = it.getIntOrNull(it.getColumnIndexOrThrow(CARD_REPS)),
                    lapses = it.getIntOrNull(it.getColumnIndexOrThrow(CARD_LAPSES)),
                    ease = it.getIntOrNull(it.getColumnIndexOrThrow(CARD_EASE)),
                    stabilityDays = it.getColumnIndex(CARD_STABILITY).takeIf { i -> i >= 0 && !it.isNull(i) }?.let { i -> it.getDouble(i) },
                )
            }
            result
        }
    }

    private fun Cursor.requireBounded() {
        if (count > MAX_PROVIDER_ROWS) {
            close()
            throw AnkiInventoryProviderException(
                AnkiInventoryFailure.PARTIAL_RESULT,
                "AnkiDroid query exceeded the bounded snapshot limit",
            )
        }
    }

    private data class Model(
        val id: Long,
        val fieldNames: List<String>,
    )

    companion object {
        private const val FIELD_SEPARATOR = "\u001f"
        private const val MAX_PROVIDER_ROWS = 250_000


        private const val NOTE_ID = "_id"
        private const val NOTE_MID = "mid"
        private const val NOTE_MOD = "mod"
        private const val NOTE_FLDS = "flds"
        private const val NOTE_TAGS = "tags"
        private const val MODEL_ID = "_id"
        private const val MODEL_NAME = "name"
        private const val MODEL_FIELD_NAMES = "field_names"
        private const val MODEL_SORT_FIELD_INDEX = "sort_field_index"
        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"
        private const val CARD_ID = "_id"
        private const val CARD_NOTE_ID = "note_id"
        private const val CARD_DECK_ID = "deck_id"
        private const val CARD_TYPE = "type"
        private const val CARD_QUEUE = "queue"
        private const val CARD_INTERVAL = "interval"
        private const val CARD_DUE = "due"
        private const val CARD_REPS = "reps"
        private const val CARD_LAPSES = "lapses"
        private const val CARD_EASE = "sm2_factor"
        private const val CARD_STABILITY = "fsrs_stability"

        private val CARD_PROJECTION = arrayOf(
            CARD_ID,
            CARD_NOTE_ID,
            CARD_DECK_ID,
            CARD_TYPE,
            CARD_QUEUE,
            CARD_INTERVAL,
            CARD_DUE,
            CARD_REPS,
            CARD_LAPSES,
            CARD_EASE,
        )

        private fun unavailable(
            failure: AnkiInventoryFailure,
            packageVersion: String? = null,
        ) = AnkiProviderCapability(
            state = CapabilityState.UNAVAILABLE,
            packageVersion = packageVersion,
            noteModificationTime = false,
            cardModificationTime = false,
            reviewHistory = false,
            failure = failure,
        )
    }
}

private fun parseFieldNames(rawData: String): List<String> {
    val trimmed = rawData.trim()
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        runCatching {
            val json = JSONArray(trimmed)
            return (0 until json.length()).map { index ->
                when (val item = json.get(index)) {
                    is JSONObject -> item.optString("name")
                    else -> item.toString()
                }
            }
        }
    }
    return rawData.split("\u001f")
}

private fun Cursor.getLongOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun Cursor.getIntOrNull(index: Int): Int? =
    if (isNull(index)) null else getInt(index)

private fun String.escapeBrowserQuery(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
