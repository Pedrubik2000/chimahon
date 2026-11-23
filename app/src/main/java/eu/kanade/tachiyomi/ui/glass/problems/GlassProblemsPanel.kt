package eu.kanade.tachiyomi.ui.glass.problems

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.util.glassDevice
import eu.kanade.tachiyomi.data.coverage.CoverageMatch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Canvas `Err-3`: the 40% scrim over the page and the 380dp Needs attention panel at the right edge. The shell
 * draws the rail after this, so the rail stays bright and keeps its touches.
 */
@Composable
fun GlassProblemsPanel(
    problems: List<Problem>,
    onClose: () -> Unit,
    onAction: (Problem, ProblemAction) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(interactionSource = null, indication = null, onClick = onClose),
        )
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .width(380.dp)
                .fillMaxHeight()
                .background(Glass.panel)
                // taps on the panel's empty parts must not reach the scrim
                .clickable(interactionSource = null, indication = null, onClick = {}),
        ) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(Glass.line))
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(MR.strings.glass_problems_title),
                            fontSize = Glass.heading,
                            lineHeight = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(stringResource(MR.strings.glass_problems_sub), color = Glass.secondary, fontSize = Glass.body)
                    }
                    Box(
                        Modifier
                            .size(Glass.button)
                            .clip(CircleShape)
                            .border(1.dp, Glass.line, CircleShape)
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlassIcon("close", size = 20.dp, contentDescription = stringResource(MR.strings.glass_problems_close))
                    }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (problems.isEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .border(1.dp, Glass.line, RoundedCornerShape(Glass.r12))
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            GlassIcon("check", size = 20.dp, tint = Glass.secondary)
                            Text(stringResource(MR.strings.glass_problems_none), color = Glass.secondary, fontSize = Glass.body)
                        }
                    }
                    problems.forEachIndexed { i, p -> ProblemCard(p, first = i == 0, onAction = { onAction(p, it) }) }
                }
                Text(
                    stringResource(MR.strings.glass_problems_footer),
                    color = Glass.secondary,
                    fontSize = Glass.caption,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

/** First card: ice line; the others: the 18% line at 62% (canvas). Status colours are white and ice only. */
@Composable
private fun ProblemCard(problem: Problem, first: Boolean, onAction: (ProblemAction) -> Unit) {
    val shape = RoundedCornerShape(Glass.r12)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (first) 1f else 0.62f)
            .border(1.dp, if (first) Glass.ice else Glass.line, shape)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIcon("alert", size = 24.dp, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            caption(problem)?.let {
                Text(it, color = Glass.ice, fontSize = Glass.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Text(title(problem), fontSize = Glass.label, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            detail(problem)?.let { Text(it, color = Glass.secondary, fontSize = Glass.body, lineHeight = 20.sp) }
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                problem.actions.forEachIndexed { i, a ->
                    Pill(actionLabel(a), onClick = { onAction(a) }, primary = i == 0)
                }
            }
        }
    }
}

@Composable
private fun caption(p: Problem): String? = when (p.kind) {
    ProblemKind.RD_REJECTED, ProblemKind.RD_NOT_PREMIUM -> stringResource(MR.strings.glass_dl_rd)
    ProblemKind.DOWNLOAD_FAILED -> p.caption ?: "AniList ${p.mediaId}"
    else -> null
}

@Composable
private fun title(p: Problem): String = when (p.kind) {
    ProblemKind.RD_REJECTED -> stringResource(MR.strings.glass_problems_rd_rejected)
    ProblemKind.RD_NOT_PREMIUM -> stringResource(MR.strings.glass_problems_rd_free)
    ProblemKind.DOWNLOAD_FAILED -> when (p.volumes.size) {
        0 -> stringResource(MR.strings.glass_problems_failed)
        1 -> stringResource(MR.strings.glass_problems_not_found_one, CoverageMatch.ranges(p.volumes))
        else -> stringResource(MR.strings.glass_problems_not_found, CoverageMatch.ranges(p.volumes))
    }
    ProblemKind.ANILIST_OFFLINE -> stringResource(MR.strings.glass_problems_offline)
    ProblemKind.ANILIST_SIGNED_OUT -> stringResource(MR.strings.glass_problems_signed_out)
}

@Composable
private fun detail(p: Problem): String? = when (p.kind) {
    ProblemKind.RD_REJECTED -> stringResource(MR.strings.glass_problems_rd_rejected_detail)
    ProblemKind.RD_NOT_PREMIUM -> stringResource(MR.strings.glass_problems_rd_free_detail)
    ProblemKind.DOWNLOAD_FAILED -> p.detail
    // the error's own text stays on Home's header line
    ProblemKind.ANILIST_OFFLINE -> stringResource(MR.strings.glass_problems_offline_detail, glassDevice())
    ProblemKind.ANILIST_SIGNED_OUT -> stringResource(MR.strings.glass_problems_signed_out_detail, glassDevice())
}

@Composable
private fun actionLabel(a: ProblemAction): String = stringResource(
    when (a) {
        ProblemAction.CHANGE_KEY -> MR.strings.glass_problems_change_key
        ProblemAction.HIDE -> MR.strings.glass_problems_hide
        ProblemAction.OPEN_TITLE -> MR.strings.glass_problems_open_title
        ProblemAction.TRY_AGAIN, ProblemAction.RETRY_ANILIST -> MR.strings.glass_problems_try_again
        ProblemAction.OPEN_LIBRARY -> MR.strings.glass_problems_open_library
        ProblemAction.SIGN_IN -> MR.strings.glass_problems_sign_in
    },
)
