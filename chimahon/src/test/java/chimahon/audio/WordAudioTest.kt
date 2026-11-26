package chimahon.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class WordAudioTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/chimahon/audio/$name")!!.readBytes()

    @Test
    fun rejectsTheNotAvailableClipButNotARealWord() {
        // fetched from JapanesePod101: kanji=ほげふが (nonsense) and kanji=猫&kana=ねこ
        assertTrue(WordAudioService.isNotAvailableClip(res("jpod_missing.mp3")))
        assertFalse(WordAudioService.isNotAvailableClip(res("jpod_neko.mp3")))
        assertFalse(WordAudioService.isNotAvailableClip(ByteArray(0)))
    }

    @Test
    fun docUnderTreeMatchesRootOrSlashPrefixOnly() {
        assertTrue(WordAudioService.isDocUnderTree("primary:Chimahon dev/android.db", "primary:Chimahon dev"))
        assertTrue(WordAudioService.isDocUnderTree("primary:Chimahon dev", "primary:Chimahon dev"))
        assertFalse(WordAudioService.isDocUnderTree("primary:Chimahon dev2/android.db", "primary:Chimahon dev"))
        assertFalse(WordAudioService.isDocUnderTree("primary:Other/android.db", "primary:Chimahon dev"))
    }

    private class Fake<T>(private var v: T) : Preference<T> {
        override fun key() = "k"
        override fun get() = v
        override fun set(value: T) { v = value }
        override fun isSet() = true
        override fun delete() {}
        override fun defaultValue() = v
        override fun changes(): Flow<T> = flowOf(v)
        override fun stateIn(scope: CoroutineScope): StateFlow<T> = throw UnsupportedOperationException()
    }

    private class Prefs(sources: String) : WordAudioPreferences {
        val src = Fake(sources)
        val seeded = Fake(false)
        override fun wordAudioEnabled() = Fake(true)
        override fun wordAudioAutoplay() = Fake(false)
        override fun wordAudioSources(): Preference<String> = src
        override fun wordAudioLocalPath() = Fake("")
        override fun wordAudioLocalUri() = Fake("")
        override fun wordAudioLocalEnabled() = Fake(false)
        override fun wordAudioDefaultSeeded(): Preference<Boolean> = seeded
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun seedsTheDefaultOnEmptyList() {
        val p = Prefs("[]")
        WordAudioSource.seedDefault(p, json)
        val list = json.decodeFromString<List<WordAudioSource>>(p.src.get())
        assertEquals(listOf(WordAudioSource.JPOD101_URL), list.map { it.url })
        assertTrue(p.seeded.get())
    }

    @Test
    fun keepsOwnSourcesFirstAndSeedsOnlyOnce() {
        val own = WordAudioSource(name = "mine", url = "https://example.com/{term}")
        val p = Prefs(json.encodeToString(listOf(own)))
        WordAudioSource.seedDefault(p, json)
        assertEquals(listOf("mine", "JapanesePod101"), json.decodeFromString<List<WordAudioSource>>(p.src.get()).map { it.name })

        // the user removes the default: it must not come back
        p.src.set("[]")
        WordAudioSource.seedDefault(p, json)
        assertEquals("[]", p.src.get())
    }
}
