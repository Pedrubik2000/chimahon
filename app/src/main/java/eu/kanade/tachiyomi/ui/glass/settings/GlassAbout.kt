package eu.kanade.tachiyomi.ui.glass.settings

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.more.settings.screen.about.OpenSourceLicensesScreen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.glass.setup.GlassSetupScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.updaterEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

// Canvas kumahon-about, option A (One card list): Settings > About. The update check and its install are the classic
// About screen's (AppUpdateChecker, AppUpdateDownloadJob); nothing here pushes the classic AboutScreen.

/** The release a check found (null: none known), and when the last check finished; kept for the sidebar line. */
private val knownUpdate = mutableStateOf<Release?>(null)
private val checkedAt = mutableLongStateOf(0L)

private fun String.versionLabel() = removePrefix("v")

/** The sidebar / phone-list line: "1.4.8 available" once a check found one, else "1.4.7 · all settings · classic view". */
@Composable
internal fun aboutLine(): String {
    val update by knownUpdate
    return update?.let { stringResource(MR.strings.glass_about_available, it.version.versionLabel()) }
        ?: stringResource(MR.strings.glass_about_line, BuildConfig.VERSION_NAME)
}

/** Up to [max] bullet lines of a release's markdown notes, with the markdown (bold, code, links) taken out. */
internal fun aboutBullets(notes: String, max: Int = 3): List<String> = notes.lines()
    .takeWhile { it.trim() != "-----" } // getLatest() joins older releases after a ----- line
    .map { it.trim() }
    .filter { it.startsWith("- ") || it.startsWith("* ") }
    .map {
        it.drop(2)
            .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace("**", "")
            .replace("`", "")
            .trim()
    }
    .filter { it.isNotEmpty() }
    .take(max)

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.line))

@Composable
private fun AboutRow(
    icon: String,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.White,
    trailing: @Composable () -> Unit = {},
    body: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassIcon(icon, tint = Glass.secondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = titleColor, fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
            body()
        }
        trailing()
    }
}

@Composable
private fun Sub(text: String) = Text(text, color = Glass.secondary, fontSize = Glass.caption)

@Composable
private fun Bullets(notes: String) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        aboutBullets(notes).forEach { Text("•  $it", fontSize = Glass.body) }
    }
}

/** The About page: the version / updates / notes / licences / GitHub panel, then the Advanced panel. */
@Composable
internal fun AboutPage(navigator: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by knownUpdate
    val checked by checkedAt
    var checking by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    // the notes load in the background; the row stays out until they are here (or for good when the fetch fails)
    var notes by remember { mutableStateOf<Release?>(null) }
    LaunchedEffect(Unit) {
        val result = try {
            AppUpdateChecker().getReleaseNotes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
        if (result is GetApplicationRelease.Result.NewUpdate) notes = result.release
    }

    var failedAt by remember { mutableLongStateOf(0L) }
    val check = check@{
        if (checking) return@check
        checking = true
        failedAt = 0L
        job = scope.launch {
            try {
                when (val result = AppUpdateChecker().checkForUpdate(context, forceCheck = true)) {
                    is GetApplicationRelease.Result.NewUpdate -> {
                        knownUpdate.value = result.release
                        checkedAt.longValue = System.currentTimeMillis()
                    }
                    is GetApplicationRelease.Result.NoNewUpdate -> {
                        knownUpdate.value = null
                        checkedAt.longValue = System.currentTimeMillis()
                    }
                    is GetApplicationRelease.Result.OsTooOld -> context.toast(MR.strings.update_check_eol)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedAt = System.currentTimeMillis()
                if (e.message.isNullOrBlank()) context.toast(MR.strings.glass_about_check_failed_toast) else context.toast(e.message)
                logcat(LogPriority.ERROR, e)
            } finally {
                checking = false
            }
        }
        Unit
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.fillMaxWidth().glassPanel(Glass.r16)) {
            val kind = if (isDebugBuildType) "Debug" else "Stable"
            AboutRow(
                "bang",
                "${stringResource(MR.strings.version)} ${BuildConfig.VERSION_NAME}",
                Modifier.clickable { copyDebugInfo(context) },
                trailing = {
                    Pill(stringResource(MR.strings.glass_about_copy_debug), { copyDebugInfo(context) }, small = true)
                },
            ) { Sub("$kind ${BuildConfig.VERSION_NAME} · ${BuildConfig.COMMIT_SHA} · ${AboutScreen.getFormattedBuildTime()}") }

            if (updaterEnabled) {
                Divider()
                val found = update
                when {
                    checking -> AboutRow(
                        "clock",
                        stringResource(MR.strings.glass_about_updates),
                        trailing = { Pill(stringResource(MR.strings.action_cancel), { job?.cancel() }, small = true) },
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = Glass.ice, strokeWidth = 2.dp)
                            Sub(stringResource(MR.strings.glass_about_checking))
                        }
                    }
                    found != null -> AboutRow(
                        "download",
                        stringResource(MR.strings.glass_about_available, found.version.versionLabel()),
                        titleColor = Glass.ice,
                        trailing = {
                            // what the classic update screen's accept button does
                            Pill(
                                stringResource(MR.strings.glass_about_install, found.version.versionLabel()),
                                { AppUpdateDownloadJob.start(context = context, url = found.downloadLink, title = found.version) },
                                small = true,
                                primary = true,
                            )
                        },
                    ) { Bullets(found.info) }
                    else -> AboutRow(
                        "clock",
                        stringResource(MR.strings.glass_about_updates),
                        trailing = {
                            Pill(
                                stringResource(if (checked == 0L) MR.strings.check_for_updates else MR.strings.glass_about_check_again),
                                check,
                                small = true,
                            )
                        },
                    ) {
                        fun ago(t: Long) = DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                        Sub(
                            when {
                                failedAt != 0L -> stringResource(MR.strings.glass_about_check_failed, ago(failedAt))
                                checked == 0L -> stringResource(MR.strings.glass_about_not_checked)
                                else -> stringResource(MR.strings.glass_about_up_to_date, ago(checked))
                            },
                        )
                    }
                }
            }

            // with an update known its row above already shows the newest notes
            val shown = notes
            if (shown != null && update == null) {
                Divider()
                AboutRow(
                    "book",
                    // the newest release is not the installed one: say so instead of "What's new"
                    stringResource(
                        if (shown.version.versionLabel() == BuildConfig.VERSION_NAME) MR.strings.glass_about_whats_new else MR.strings.glass_about_latest_release,
                        shown.version.versionLabel(),
                    ),
                    trailing = {
                        Pill(stringResource(MR.strings.glass_about_full_notes), { context.openInBrowser(shown.releaseLink) }, small = true, icon = "external")
                    },
                ) { Bullets(shown.info) }
            }

            Divider()
            AboutRow(
                "list",
                stringResource(MR.strings.licenses),
                Modifier.clickable { navigator.push(OpenSourceLicensesScreen()) },
                trailing = { GlassIcon("chevRight", tint = Glass.secondary) },
            ) { Sub(stringResource(MR.strings.glass_about_licences_sub)) }

            Divider()
            AboutRow(
                "compass",
                "GitHub",
                Modifier.clickable { context.openInBrowser("https://github.com/Pedrubik2000/kumahon") },
                trailing = { GlassIcon("external", tint = Glass.secondary) },
            ) { Sub(stringResource(MR.strings.glass_about_github_sub)) }
        }

        Text(
            stringResource(MR.strings.glass_about_advanced).uppercase(),
            color = Glass.secondary,
            fontSize = Glass.caption,
            fontWeight = FontWeight.SemiBold,
        )
        Column(Modifier.fillMaxWidth().glassPanel(Glass.r16)) {
            AdvancedRow("gear", MR.strings.glass_settings_all, MR.strings.glass_settings_all_sub) { navigator.push(SettingsScreen()) }
            Divider()
            AdvancedRow("home", MR.strings.glass_classic_view, MR.strings.glass_settings_classic_sub) { navigator.push(HomeScreen) }
            Divider()
            // chimahon: the first-run setup again (it only reads until something is tapped)
            AdvancedRow("undo", MR.strings.glass_setup_run_again, MR.strings.glass_setup_run_again_sub) {
                navigator.push(GlassSetupScreen(fromSettings = true))
            }
        }
    }
}

@Composable
private fun AdvancedRow(
    icon: String,
    title: StringResource,
    sub: StringResource,
    onClick: () -> Unit,
) = AboutRow(
    icon,
    stringResource(title),
    Modifier.clickable(onClick = onClick),
    trailing = { GlassIcon("chevRight", tint = Glass.secondary) },
) { Sub(stringResource(sub)) }

/** The classic About's Version row action. */
private fun copyDebugInfo(context: Context) {
    context.copyToClipboard("Debug information", CrashLogUtil(context).getDebugInfo())
}
