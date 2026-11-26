package chimahon.audio

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class WordAudioSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    var isEnabled: Boolean = true,
    val isDefault: Boolean = false,
    val type: SourceType = SourceType.ONLINE,
) {
    enum class SourceType {
        LOCAL,
        ONLINE,
    }

    companion object {
        const val LOCAL_URL = "chimahon-local://android.db"

        const val JPOD101_URL =
            "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji={term}&kana={reading}"

        /** Direct audio (no setup): the plain-audio branch of WordAudioService handles it. */
        fun createJpod101() = WordAudioSource(
            id = "default_jpod101",
            name = "JapanesePod101",
            url = JPOD101_URL,
            isDefault = true,
        )

        /** Adds the default source last, once. Own sources stay first. */
        fun seedDefault(prefs: WordAudioPreferences, json: kotlinx.serialization.json.Json) {
            if (prefs.wordAudioDefaultSeeded().get()) return
            val own = try {
                json.decodeFromString<List<WordAudioSource>>(prefs.wordAudioSources().get())
            } catch (e: Exception) {
                return // unreadable list: never overwrite the user's sources; try again next start
            }
            prefs.wordAudioSources().set(json.encodeToString(own + createJpod101()))
            prefs.wordAudioDefaultSeeded().set(true)
        }

        fun createLocal() = WordAudioSource(
            id = "local_audio",
            name = "Local Audio",
            url = LOCAL_URL,
            type = SourceType.LOCAL,
        )
    }
}

data class WordAudioResult(
    val name: String,
    val url: String,
    val source: WordAudioSource,
    val data: ByteArray? = null, // For local audio bytes
)
