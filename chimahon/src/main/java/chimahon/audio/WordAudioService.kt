package chimahon.audio

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class WordAudioService(
    private val context: Context,
    private val preferences: WordAudioPreferences = Injekt.get(),
    private val network: NetworkHelper = Injekt.get(),
) {
    private val localDatabase = WordAudioDatabase(context)
    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // Browser-like UA so endpoints such as Google's translate_tts accept the request
    // (the app's default User-Agent is rejected with a 403).
    private val browserUserAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private var cachedSources: List<WordAudioSource>? = null
    private var cachedSourcesRaw: String = ""

    /** The saved local database lost its SAF grant (reinstall): word audio needs it picked again. */
    @Volatile
    var localAccessLost: Boolean = false
        private set

    companion object {
        private const val TAG = "WordAudioService"

        // JapanesePod101 answers 200 with this "audio not available" clip instead of 404 (Yomichan rejects it too).
        private const val NOT_AVAILABLE_SHA256 = "ae6398b5a27bc8c0a771df6c907ade794be15518174773c58c7c7ddd17098906"

        fun isNotAvailableClip(bytes: ByteArray): Boolean =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) } == NOT_AVAILABLE_SHA256

        /** True when [uriStr] is a saved Uri whose persisted read grant is gone. */
        fun isGrantLost(context: Context, uriStr: String): Boolean =
            uriStr.isNotBlank() && resolveGrantedUri(context, uriStr) == null

        /** Doc id [docId] is the tree root [treeDocId] itself or lies under it. */
        fun isDocUnderTree(docId: String, treeDocId: String): Boolean =
            docId == treeDocId || docId.startsWith("$treeDocId/")

        /**
         * The uri to open for the saved [uriStr]: itself when its own grant persists, else the same document
         * through a persisted read TREE grant that contains it, else null (access lost).
         */
        fun resolveGrantedUri(context: Context, uriStr: String): android.net.Uri? {
            val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }
            if (grants.any { it.uri.toString() == uriStr }) return android.net.Uri.parse(uriStr)
            return try {
                val doc = android.net.Uri.parse(uriStr)
                val docId = android.provider.DocumentsContract.getDocumentId(doc)
                grants.map { it.uri }.firstNotNullOfOrNull { tree ->
                    if (tree.authority != doc.authority ||
                        !android.provider.DocumentsContract.isTreeUri(tree)
                    ) {
                        return@firstNotNullOfOrNull null
                    }
                    val treeId = android.provider.DocumentsContract.getTreeDocumentId(tree)
                    if (isDocUnderTree(docId, treeId)) {
                        android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    } else {
                        null
                    }
                }
            } catch (e: IllegalArgumentException) {
                null // not a document uri
            }
        }
    }

    init {
        WordAudioSource.seedDefault(preferences, json)
    }

    /**
     * Scans all enabled sources in priority order to find audio for a term.
     */
    suspend fun findWordAudio(term: String, reading: String): List<WordAudioResult> = withContext(Dispatchers.IO) {
        if (!preferences.wordAudioEnabled().get()) return@withContext emptyList()

        val results = mutableListOf<WordAudioResult>()
        val sources = getEnabledSources()

        for (source in sources) {
            try {
                when (source.type) {
                    WordAudioSource.SourceType.LOCAL -> {
                        val localEntries = findLocalAudio(term, reading)
                        results.addAll(
                            localEntries.map { entry ->
                                WordAudioResult(
                                    name = "${entry.sourceId} (${entry.speaker ?: entry.display ?: "Local"})",
                                    url = "chimahon-local://${entry.sourceId}/${entry.file}",
                                    source = source,
                                    data = null, // Fetched on demand via getAudioData
                                )
                            },
                        )
                    }
                    WordAudioSource.SourceType.ONLINE -> {
                        val onlineResults = fetchOnlineAudio(term, reading, source)
                        results.addAll(onlineResults)
                    }
                }

                if (results.isNotEmpty()) break
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching audio from source: ${source.name}", e)
            }
        }

        results
    }

    suspend fun getAudioData(filePath: String, sourceId: String): ByteArray? = withContext(Dispatchers.IO) {
        localDatabase.getAudioData(filePath, sourceId)
    }

    private val tempAudioFile by lazy { File(context.cacheDir, "word_audio_track") }

    suspend fun getAudioDataFd(filePath: String, sourceId: String): ParcelFileDescriptor? = withContext(Dispatchers.IO) {
        val blob = localDatabase.getAudioData(filePath, sourceId) ?: return@withContext null
        tempAudioFile.writeBytes(blob)
        ParcelFileDescriptor.open(tempAudioFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun getEnabledSources(): List<WordAudioSource> {
        val raw = preferences.wordAudioSources().get()
        if (raw != cachedSourcesRaw || cachedSources == null) {
            cachedSources = try {
                json.decodeFromString<List<WordAudioSource>>(raw)
            } catch (e: Exception) {
                emptyList()
            }
            cachedSourcesRaw = raw
        }
        val sources = cachedSources!!

        val localEnabled = preferences.wordAudioLocalEnabled().get()
        val finalSources = sources.toMutableList()

        if (localEnabled && finalSources.none { it.type == WordAudioSource.SourceType.LOCAL }) {
            finalSources.add(0, WordAudioSource.createLocal())
        }

        return finalSources.filter { it.isEnabled }
    }

    private fun findLocalAudio(term: String, reading: String): List<WordAudioDatabase.LocalEntry> {
        localAccessLost = false // set again below only when this lookup hits a lost grant
        // New path: SAF Uri
        val uriStr = preferences.wordAudioLocalUri().get()
        if (uriStr.isNotBlank()) {
            val openUri = resolveGrantedUri(context, uriStr)
            if (openUri == null) {
                localAccessLost = true
                return emptyList()
            }
            val openStr = openUri.toString() // the saved doc uri, or the same doc through a tree grant
            // SAF providers can invalidate or replace a descriptor while the
            // process is alive. Re-probe the schema before using a cached
            // native handle so we can reopen and use the stable-copy fallback.
            if (!localDatabase.isOpenFor(openStr) || !localDatabase.testConnection()) {
                localDatabase.close()
                if (!localDatabase.updateUri(openUri) || !localDatabase.testConnection()) {
                    localAccessLost = localDatabase.accessLost
                    return emptyList()
                }
                if (localDatabase.fallbackUsed) {
                    // Keep using the verified private copy if the provider
                    // supplied a virtual or unstable descriptor.
                    preferences.wordAudioLocalPath().set(
                        File(context.getExternalFilesDir(null), "word_audio.db").absolutePath,
                    )
                    preferences.wordAudioLocalUri().set("")
                }
            }
            localAccessLost = false
            return localDatabase.findEntries(term, reading)
        }

        // Legacy path: file path
        val path = preferences.wordAudioLocalPath().get()
        if (path.isBlank()) return emptyList()
        localDatabase.updatePath(path)
        return localDatabase.findEntries(term, reading)
    }

    private suspend fun fetchOnlineAudio(term: String, reading: String, source: WordAudioSource): List<WordAudioResult> {
        val url = source.url
            .replace("{term}", term)
            .replace("{reading}", reading)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", browserUserAgent)
            .build()

        return try {
            network.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()

                val bytes = response.body?.bytes() ?: return emptyList()
                if (isNotAvailableClip(bytes)) return emptyList()

                if (bytes.decodeToString(endIndex = minOf(bytes.size, 64)).trim().startsWith("{")) {
                    parseYomitanJson(bytes.decodeToString(), source)
                } else {
                    // The final url (JPod101 redirects to its CDN), so playing and mining fetch the same file.
                    listOf(WordAudioResult(name = source.name, url = response.request.url.toString(), source = source))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error fetching online audio", e)
            emptyList()
        }
    }

    private fun parseYomitanJson(jsonStr: String, source: WordAudioSource): List<WordAudioResult> {
        return try {
            val jsonObj = json.parseToJsonElement(jsonStr) as? JsonObject ?: return emptyList()

            if ((jsonObj["type"] as? JsonPrimitive)?.content != "audioSourceList") {
                return emptyList()
            }

            val sourcesArray = jsonObj["audioSources"] as? JsonArray ?: return emptyList()

            sourcesArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.content ?: source.name
                val url = (obj["url"] as? JsonPrimitive)?.content ?: return@mapNotNull null

                WordAudioResult(name = name, url = url, source = source)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Yomitan JSON", e)
            emptyList()
        }
    }

    suspend fun fetchRemoteAudioData(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", browserUserAgent)
            .build()
        try {
            network.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes()?.takeUnless { isNotAvailableClip(it) } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching remote audio data", e)
            null
        }
    }
}
