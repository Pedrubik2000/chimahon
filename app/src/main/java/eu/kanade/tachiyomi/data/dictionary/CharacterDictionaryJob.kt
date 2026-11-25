package eu.kanade.tachiyomi.data.dictionary

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import chimahon.DictionaryRepository
import chimahon.chardict.AndroidImages
import chimahon.chardict.CharacterDictionary
import chimahon.chardict.CharacterDictionaryBuilder
import eu.kanade.presentation.more.settings.screen.importDictionaryFromStream
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import chimahon.ocr.KumapieColours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * kumahon's own character dictionary (kumapie's `reading_characters.py` on the device): ONE zip for every work the reader opened,
 * built from AniList only when the set of works changed, imported over the previous one (same title), enabled and put first in
 * every dictionary profile so a name's character entry shows on top.
 * Scheduled a minute after a manga opens (debounce), on an unmetered network (portraits); "Rebuild now" runs at once on any network.
 */
class CharacterDictionaryJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val manual = inputData.getBoolean(MANUAL, false)
        if (!manual && !CharacterDictionary.needsRebuild(app)) return Result.success()
        val works = CharacterDictionary.works(app)
        if (works.isEmpty()) return Result.success()
        // one build at a time: a REPLACEd job's blocking build may still be running; this one tries again later and touches nothing
        if (!running.compareAndSet(false, true)) return Result.retry()

        val zip = File(app.cacheDir, "kumahon-characters-${System.currentTimeMillis()}.zip")
        val repository = Injekt.get<DictionaryRepository>()
        var replacing = false // the import started
        var before = ""
        val mine = java.util.concurrent.atomic.AtomicReference("") // this job's own status text
        fun say(s: String) { mine.set(s); CharacterDictionary.setBuilding(app, s) }
        fun clearStatus() { CharacterDictionary.clearBuilding(app, mine.get()) } // a newer job's "waiting" stays
        try {
            setForegroundSafely()
            CharacterDictionary.verify(app)
            say("${works.size} works")
            val built = withContext(Dispatchers.IO) {
                CharacterDictionaryBuilder(
                    images = AndroidImages::download,
                    resizer = AndroidImages.resizer,
                    onProgress = { say(it) },
                ).buildForTitles(works, zip)
            }
            CharacterDictionary.recordNotFound(app, built.notFound)
            // nothing resolved: keep the old dictionary; some did: the rest stay out of builtWorks, so needsRebuild retries them
            check(built.resolved.isNotEmpty()) { built.error ?: "no work found on AniList: ${built.unresolved.joinToString("; ")}" }
            say("importing")
            val prefs = Injekt.get<DictionaryPreferences>()
            val title = CharacterDictionary.TITLE
            val added = prefs.profileStore.getProfiles().filter { title !in it.dictionaryOrder }.map { it.id }.toSet()
            // once the import starts it is finished, marked and put first even when the job is cancelled meanwhile
            withContext(NonCancellable) {
                // readers and faces leave the folder before it is replaced (the repository opens no session while importing is set)
                before = CharacterDictionary.fingerprint(app)
                replacing = true
                CharacterDictionary.importing = true
                CharacterDictionary.release()
                repository.close()
                val (message, ok) = zip.inputStream().use { importDictionaryFromStream(app, it, prefs.profileStore.getActiveProfile()) }
                check(ok) { message }
                DictionaryImportMutex.withLock { putFirst(prefs, added) }
                CharacterDictionary.markBuilt(app, built.resolved, built.characters, built.notFound, mine.get())
                replacing = false
            }
            if (works.size > built.resolved.size + built.notFound.size) {
                CharacterDictionary.setError(app, "some works could not be fetched, retried later")
            }
            Log.i(TAG, "built: ${built.characters} characters from ${built.resolved.size} works, ${built.notFound.size} not found on AniList")
            return Result.success()
        } catch (e: CancellationException) {
            clearStatus()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "build failed", e)
            clearStatus()
            CharacterDictionary.setError(app, e.message ?: e.toString())
            return Result.failure()
        } finally {
            // a half-written folder: forget it, nothing opens it; an untouched one (the old dictionary) stays
            if (replacing && CharacterDictionary.fingerprint(app) != before) CharacterDictionary.reset(app)
            repository.close() // no session survives on files that were being replaced
            zip.delete()
            CharacterDictionary.importing = false
            running.set(false)
            KumapieColours.clear() // faces asked meanwhile were answered empty
        }
    }

    /** In a profile that did not have the dictionary yet (its [added] ids): first in the order and enabled where it lists enabled dictionaries (empty = all). The others keep what the user set. */
    private fun putFirst(prefs: DictionaryPreferences, added: Set<String>) {
        val title = CharacterDictionary.TITLE
        val store = prefs.profileStore
        for (p in store.getProfiles()) {
            if (p.id !in added) continue
            val order = listOf(title) + p.dictionaryOrder.filter { it.isNotBlank() && it != title }
            val enabled = if (p.enabledDictionaries.isNotEmpty() && title !in p.enabledDictionaries) p.enabledDictionaries + title else p.enabledDictionaries
            if (order != p.dictionaryOrder || enabled != p.enabledDictionaries) {
                store.updateProfile(p.copy(dictionaryOrder = order, enabledDictionaries = enabled))
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_DICTIONARY_UPDATE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Building the character dictionary")
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            Notifications.ID_CHARACTER_DICT_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    companion object {
        private const val TAG = "CharacterDictionary"
        private const val MANUAL = "manual"
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)

        /** A minute from now, unmetered, and only when the set of works changed; a build already waiting or running is kept. */
        fun schedule(context: Context) {
            if (!CharacterDictionary.needsRebuild(context)) return
            val request = OneTimeWorkRequestBuilder<CharacterDictionaryJob>()
                .addTag(TAG)
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        /** The Settings button: now, any network, even when the set did not change. */
        fun rebuildNow(context: Context) {
            if (CharacterDictionary.works(context).isEmpty()) return
            val request = OneTimeWorkRequestBuilder<CharacterDictionaryJob>()
                .addTag(TAG)
                .setInputData(Data.Builder().putBoolean(MANUAL, true).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            CharacterDictionary.setBuilding(context, "waiting")
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
