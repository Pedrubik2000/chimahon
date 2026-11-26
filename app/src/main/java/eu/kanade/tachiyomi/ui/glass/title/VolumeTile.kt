package eu.kanade.tachiyomi.ui.glass.title

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.data.mangadex.VolumeCovers
import java.io.File
import androidx.compose.runtime.remember
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.StatusColours
import eu.kanade.presentation.glass.StatusPalette
import eu.kanade.presentation.glass.glassPanel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private val STATES = listOf(
    VolState.READ to MR.strings.glass_dl_key_read,
    VolState.HERE to MR.strings.glass_legend_here,
    VolState.DOWNLOADING to MR.strings.glass_dl_key_downloading,
    VolState.QUEUED to MR.strings.glass_dl_queued,
    VolState.FAILED to MR.strings.glass_dl_key_failed,
    VolState.NONE to MR.strings.glass_dl_key_none,
)

/**
 * A volume as a cover (2:3): the cover dimmed per state, [number] top-left, ONE state badge top-right, a border for
 * the state and, while downloading, a progress strip at the bottom. [number] null = the legend's mini swatch.
 * [overlay] draws on top (select mode); [current] = the ice ring (the volume to read, or a picked one).
 * The caller's [modifier] sets the size and any click.
 */
@Composable
internal fun VolumeTile(
    state: VolState,
    number: Int?,
    image: Any?,
    modifier: Modifier = Modifier,
    fraction: Float? = null,
    standIn: Boolean = false,
    blurred: Boolean = false,
    current: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val style = StatusPalette.current().of(state)
    val mini = number == null
    val shape = RoundedCornerShape(if (mini) 4.dp else Glass.r8)
    val ring = current
    val border = if (ring) Glass.ice else style.border
    val width = if (ring) 2.dp else if (mini) style.borderWidth.coerceAtMost(1.5.dp) else style.borderWidth
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(Glass.panel)
            .dashedBorder(border, width, style.dashed && !ring, shape),
    ) {
        // blurred = covers beyond recognition (needs Android 12; older: no image at all)
        if (image != null && (!blurred || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = style.dim * (if (standIn) 0.6f else 1f),
                modifier = Modifier.fillMaxSize().then(if (blurred) Modifier.blur(18.dp) else Modifier),
            )
        } else if (image == null) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.10f * style.dim)))
        }
        if (number != null) {
            Text(
                number.toString(),
                fontSize = Glass.caption,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .heightIn(min = 20.dp)
                    .widthIn(min = 20.dp)
                    .clip(CircleShape)
                    .background(Glass.scrim)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        style.glyph?.let { glyph ->
            val badge = if (mini) 14.dp else 22.dp
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(if (mini) 2.dp else 4.dp)
                    .size(badge)
                    .clip(CircleShape)
                    .background(style.badgeFill ?: Glass.scrim),
                contentAlignment = Alignment.Center,
            ) {
                GlassIcon(glyph, size = if (mini) 9.dp else 14.dp, tint = style.ink)
            }
        }
        if (state == VolState.DOWNLOADING) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .heightIn(min = if (mini) 3.dp else 5.dp)
                    .background(Color.Black.copy(alpha = 0.6f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (mini) 0.6f else (fraction ?: 0f))
                        .heightIn(min = if (mini) 3.dp else 5.dp)
                        .background(style.ink),
                )
            }
        }
        overlay()
    }
}

/** A border drawn over the content, solid or dashed; none when [color] is null. */
internal fun Modifier.dashedBorder(color: Color?, width: Dp, dashed: Boolean, shape: RoundedCornerShape): Modifier =
    if (color == null) {
        this
    } else {
        drawWithContent {
            drawContent()
            val w = width.toPx()
            val radius = (shape.topStart.toPx(size, this) - w / 2).coerceAtLeast(0f)
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(radius),
                style = Stroke(w, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(w * 3, w * 2)) else null),
            )
        }
    }

/**
 * The one legend (title page on phone and tablet, Downloads page): the six states as mini covers with their badge,
 * then the two kinds of source box. [cover] = the image the mini covers show (null = a plain panel).
 */
@Composable
internal fun StatusLegend(modifier: Modifier = Modifier, cover: Any? = null) {
    val colours = StatusPalette.current()
    Column(modifier.fillMaxWidth().glassPanel(Glass.r12).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Caption(stringResource(MR.strings.glass_legend_volumes))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            STATES.forEach { (state, label) ->
                LegendItem(stringResource(label)) {
                    VolumeTile(state, null, cover, Modifier.width(26.dp), fraction = 0.6f)
                }
            }
        }
        Caption(stringResource(MR.strings.glass_legend_boxes))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LegendItem(stringResource(MR.strings.glass_legend_box_have)) {
                SourceBoxCell(7, BoxState.HAVE, colours, Modifier.width(28.dp))
            }
            LegendItem(stringResource(MR.strings.glass_legend_box_gap)) {
                SourceBoxCell(null, BoxState.NONE, colours, Modifier.width(96.dp), gapText = stringResource(MR.strings.glass_title_gap, "20–24"))
            }
        }
    }
}

@Composable
private fun Caption(text: String) =
    Text(text, color = Glass.secondary, fontSize = Glass.caption, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun LegendItem(label: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        swatch()
        Text(label, fontSize = Glass.body)
    }
}

/** A volume's picture: its own first page, else MangaDex's cover (with its headers), else the series cover. */
@Composable
internal fun volumeImage(localCover: File?, cover: String?, seriesCover: String?): Any? {
    val context = LocalPlatformContext.current
    return when {
        localCover != null -> localCover
        cover == null -> seriesCover
        else -> remember(cover) { ImageRequest.Builder(context).data(cover).httpHeaders(VolumeCovers.imageHeaders).build() }
    }
}
