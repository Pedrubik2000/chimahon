package eu.kanade.presentation.glass

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage

/** kumapie's "Glass" look for chimahon (canvas rules in plans/chimahon_ui_plan.md). Panels are solid, no blur. */
object Glass {
    val page = Color(0xFF07090D)
    val panel = Color(0xFF14171D)
    val line = Color.White.copy(alpha = 0.18f)
    val ice = Color(0xFF9CC9FF)
    val secondary = Color.White.copy(alpha = 0.62f)
    val glass = Color(0xFF07090D).copy(alpha = 0.55f)
    val scrim = Color(0xFF07090D).copy(alpha = 0.85f)
    val bear = Color(0xFFC98A4B)

    /** Review: new / again / due counts and the due badge (canvas Review option C). */
    val blue = Color(0xFF6FA8FF)
    val red = Color(0xFFFF6B6B)
    val green = Color(0xFF7CC47F)

    /** Coverage box states. */
    val have = Color.White.copy(alpha = 0.85f)
    val none = Color.White.copy(alpha = 0.12f)

    val caption = 12.sp
    val body = 14.sp
    val label = 16.sp
    val heading = 22.sp
    val title = 32.sp
    val hero = 40.sp

    val r8 = 8.dp
    val r10 = 10.dp
    val r12 = 12.dp
    val r16 = 16.dp

    val button = 48.dp
    val chip = 40.dp
    val rail = 56.dp
}

fun Modifier.glassPanel(radius: Dp = Glass.r16): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).background(Glass.panel).border(1.dp, Glass.line, shape)
}

private fun Modifier.outlined(shape: Shape) =
    clip(shape).background(Glass.glass).border(1.dp, Glass.line, shape)

val GlassRailItems = listOf(
    Triple("home", "Home", "home"),
    Triple("library", "Library", "library"),
    Triple("discover", "Discover", "compass"),
    Triple("releases", "Releases", "calendar"),
    Triple("review", "Review", "cards"),
    Triple("downloads", "Downloads", "download"),
    Triple("settings", "Settings", "gear"),
)

/** Cards due for review, shown as the green badge on the Review rail/bar item; the Review model keeps it current. Main thread only. */
val glassReviewDue = mutableIntStateOf(0)

/** Volumes out since Releases was last opened: the ice badge on the Releases rail/bar item (the shell and the page keep it current). Main thread only. */
val glassReleasesNew = mutableIntStateOf(0)

/** The text of the Review badge: null at 0. */
fun dueBadge(n: Int): String? = if (n <= 0) null else if (n > 99) "99+" else "$n"

/** The bear + five round buttons; [active] is one of the [GlassRailItems] ids (or "none"). */
/** Root of every glass screen: the page colour behind the system bars, white sans-serif text whatever the app theme's font is. */
@Composable
fun GlassPage(content: @Composable BoxScope.() -> Unit) {
    GlassMaterialTheme {
        CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalTextStyle provides TextStyle(fontFamily = FontFamily.SansSerif, color = Color.White),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Glass.page)
                    .windowInsetsPadding(WindowInsets.systemBars),
                content = content,
            )
        }
    }
}

/** Light clock/icons on the dark page; the previous look returns when the glass screen leaves (classic screens keep their own). */
@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val was = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (was != null) controller.isAppearanceLightStatusBars = was }
    }
}

/** The one dark Material3 scheme built from the Glass tokens: stock sheets, dialogs, menus, progress, dividers and switches inside the glass UI and the reader inherit it. */
private val GlassColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    secondary = Glass.ice,
    onSecondary = Glass.page,
    secondaryContainer = Color.White.copy(alpha = 0.12f),
    onSecondaryContainer = Color.White,
    tertiary = Glass.ice,
    onTertiary = Glass.page,
    background = Glass.page,
    onBackground = Color.White,
    surface = Glass.panel,
    onSurface = Color.White,
    surfaceVariant = Glass.panel,
    onSurfaceVariant = Glass.secondary,
    surfaceTint = Glass.panel,
    surfaceBright = Glass.panel,
    surfaceDim = Glass.page,
    surfaceContainerLowest = Glass.page,
    surfaceContainerLow = Glass.panel,
    surfaceContainer = Glass.panel,
    surfaceContainerHigh = Glass.panel,
    surfaceContainerHighest = Glass.panel,
    outline = Glass.line,
    outlineVariant = Color.White.copy(alpha = 0.08f),
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
)

@Composable
fun GlassMaterialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GlassColorScheme,
        shapes = MaterialTheme.shapes.copy(extraLarge = RoundedCornerShape(Glass.r16)),
        typography = MaterialTheme.typography,
        content = content,
    )
}

@Composable
fun GlassRail(
    active: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBearLongClick: () -> Unit = {},
    /** rail ids with an ice dot (an area that needs attention, canvas `Err-3`) */
    dots: Set<String> = emptySet(),
) {
    Column(
        modifier.width(Glass.rail),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(bottom = 26.dp)
                .size(Glass.rail)
                .clip(CircleShape)
                .background(Glass.bear)
                .combinedClickable(onClick = {}, onLongClick = onBearLongClick)
                .semantics { contentDescription = "chimahon" },
            contentAlignment = Alignment.Center,
        ) {
            Text("熊", color = Color.Black, fontSize = Glass.heading, fontWeight = FontWeight.Bold)
        }
        GlassRailItems.forEach { (id, label, icon) ->
            val dot = id in dots
            val due = if (id == "review") glassReviewDue.intValue else 0
            val fresh = if (id == "releases") glassReleasesNew.intValue else 0
            GlassRailButton(
                icon = icon,
                label = listOfNotNull(label, "needs attention".takeIf { dot }, "$due due".takeIf { due > 0 }, "$fresh new".takeIf { fresh > 0 }).joinToString(", "),
                on = id == active,
                onClick = { onSelect(id) },
                dot = dot,
                badge = dueBadge(due + fresh),
                badgeColor = if (fresh > 0) Glass.ice else Glass.green,
            )
        }
    }
}

/** One 56dp round rail button: white when [on]; [dot] = 14dp ice dot top right; [badge] = ice count top right. */
@Composable
fun GlassRailButton(
    icon: String,
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dot: Boolean = false,
    badge: String? = null,
    badgeColor: Color = Glass.ice,
) {
    // the dot and badge sit outside the button's circle clip
    Box(modifier.size(Glass.rail)) {
        Box(
            Modifier
                .matchParentSize()
                .then(if (on) Modifier.clip(CircleShape).background(Color.White) else Modifier.outlined(CircleShape))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            GlassIcon(icon, size = 24.dp, tint = if (on) Color.Black else Color.White, contentDescription = label)
        }
        // 2dp page-coloured ring around both, as on the canvas
        if (dot) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-1).dp, y = 1.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Glass.page)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Glass.ice),
            )
        }
        badge?.let {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .heightIn(min = 24.dp)
                    .widthIn(min = 24.dp)
                    .clip(CircleShape)
                    .background(Glass.page)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(it, color = Color.Black, fontSize = Glass.caption, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** White = primary; otherwise dark glass with the 18% line. [small] = the 40dp Back/secondary size. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: String? = null,
    small: Boolean = false,
) {
    val ink = if (primary) Color.Black else Color.White
    Row(
        modifier
            .height(if (small) Glass.chip else Glass.button)
            .then(if (primary) Modifier.clip(CircleShape).background(Color.White) else Modifier.outlined(CircleShape))
            .clickable(onClick = onClick)
            .padding(start = if (icon != null && small) 10.dp else if (small) 16.dp else 22.dp, end = if (small) 16.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (small) 4.dp else 8.dp),
    ) {
        icon?.let { GlassIcon(it, size = if (small) 20.dp else 18.dp, tint = ink) }
        Text(
            text,
            color = ink,
            fontSize = if (small) Glass.body else Glass.label,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A chip; selected = white. [detail] is the ice number after the text (AniList tag rank). */
@Composable
fun GlassChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    /** a count after the text, in the text colour at 62% (Library "Reading 56") */
    count: String? = null,
    /** trailing icon ("chevDown" for menus) */
    icon: String? = null,
) {
    val ink = if (selected) Color.Black else Color.White
    Row(
        modifier
            .height(Glass.chip)
            .then(if (selected) Modifier.clip(CircleShape).background(Color.White) else Modifier.outlined(CircleShape))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text,
            color = ink,
            fontSize = Glass.body,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        detail?.let { Text(it, color = Glass.ice, fontSize = Glass.caption) }
        count?.let { Text(it, color = ink.copy(alpha = 0.62f), fontSize = Glass.body, maxLines = 1) }
        icon?.let { GlassIcon(it, size = 16.dp, tint = ink) }
    }
}

/** 4dp bar: white for known %, ice for reading progress. */
@Composable
fun Meter(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    label: String? = null,
) {
    val bar: @Composable (Modifier) -> Unit = { m ->
        Box(m.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Glass.line)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
        }
    }
    if (label == null) {
        bar(modifier)
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            bar(Modifier.weight(1f))
            Text(label, color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
        }
    }
}

/** 2:3 cover (radius 10, no shadow), title under it; optional top-left badge, bottom progress bar, meter slot. */
@Composable
fun CoverCard(
    cover: Any?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    progress: Float? = null,
    progressColor: Color = Glass.ice,
    meter: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Glass.r10)
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(Glass.panel)
                .border(1.dp, Glass.line, shape),
        ) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            badge?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = Glass.caption,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(4.dp)
                        .heightIn(min = 20.dp)
                        .widthIn(min = 20.dp)
                        .clip(CircleShape)
                        .background(Glass.scrim)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            progress?.let {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f)),
                ) {
                    Box(Modifier.fillMaxWidth(it.coerceIn(0f, 1f)).fillMaxHeight().background(progressColor))
                }
            }
        }
        Text(title, color = Color.White, fontSize = Glass.body, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        meter?.invoke()
    }
}

/** One 12dp box per volume, each its state colour ([Glass.have], [Glass.none], [Glass.ice]). */
@Composable
fun CoverageBar(cells: List<Color>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        cells.forEach { Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(2.dp)).background(it)) }
    }
}

/** 44x24 switch: ice when on. */
@Composable
fun GlassToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp, 24.dp)
            .clip(CircleShape)
            .background(if (checked) Glass.ice else Glass.line)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Box(
            Modifier
                .offset(x = if (checked) 23.dp else 3.dp, y = 3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) Glass.page else Color.White),
        )
    }
}
