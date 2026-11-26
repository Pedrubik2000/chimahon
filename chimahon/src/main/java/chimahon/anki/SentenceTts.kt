package chimahon.anki

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sentence audio from Android's own TextToSpeech (Japanese), as WAV bytes. No other app involved.
 * One engine per call, shut down at the end (mining is rare; no idle bookkeeping).
 */
object SentenceTts {
    private const val TAG = "KumahonAudio"
    private const val INIT_TIMEOUT_S = 5L
    private const val SYNTH_TIMEOUT_S = 15L

    /** True when [text] has kana or kanji worth reading. */
    fun hasJapanese(text: String): Boolean =
        text.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }

    /** True when the engine starts and has a Japanese voice. Blocking (up to 5 s): call off the main thread. */
    /** True: a Japanese voice; false: the engine runs without one; null: it did not start in time (ask again later). */
    fun available(context: Context): Boolean? {
        var tts: TextToSpeech? = null
        return try {
            val ready = CountDownLatch(1)
            var status = TextToSpeech.ERROR
            tts = TextToSpeech(context.applicationContext, { status = it; ready.countDown() }, "com.google.android.tts")
            if (!ready.await(INIT_TIMEOUT_S, TimeUnit.SECONDS)) return null
            status == TextToSpeech.SUCCESS && tts.isLanguageAvailable(Locale.JAPANESE) >= TextToSpeech.LANG_AVAILABLE
        } catch (e: Exception) {
            false
        } finally {
            runCatching { tts?.shutdown() }
        }
    }

    /** Blocking, up to ~20 s worst case: call off the main thread. Null on any failure (one INFO line). */
    fun say(context: Context, text: String): ByteArray? {
        val sentence = text.trim()
        if (!hasJapanese(sentence)) return null
        var tts: TextToSpeech? = null
        val file = File(context.cacheDir, "tts_${UUID.randomUUID()}.wav")
        return try {
            val ready = CountDownLatch(1)
            var status = TextToSpeech.ERROR
            // Google TTS has Japanese; Android falls back to the default engine when it is not installed
            tts = TextToSpeech(context.applicationContext, { status = it; ready.countDown() }, "com.google.android.tts")
            if (!ready.await(INIT_TIMEOUT_S, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) {
                return fail("TTS engine did not start (status=$status)")
            }
            val engine = tts
            if (engine.isLanguageAvailable(Locale.JAPANESE) < TextToSpeech.LANG_AVAILABLE) {
                return fail("no Japanese voice installed")
            }
            engine.language = Locale.JAPANESE
            val done = CountDownLatch(1)
            var ok = false
            val id = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id) { ok = true; done.countDown() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id) done.countDown() }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) done.countDown() }
            })
            if (engine.synthesizeToFile(sentence, Bundle(), file, id) != TextToSpeech.SUCCESS) {
                return fail("synthesizeToFile refused the request")
            }
            if (!done.await(SYNTH_TIMEOUT_S, TimeUnit.SECONDS) || !ok) return fail("synthesis failed or timed out")
            file.readBytes().takeIf { it.size > 44 } ?: fail("empty audio")
        } catch (e: Exception) {
            fail("$e")
        } finally {
            runCatching { tts?.shutdown() }
            file.delete()
        }
    }

    private fun fail(why: String): ByteArray? {
        Log.i(TAG, "no sentence audio: $why")
        return null
    }
}
