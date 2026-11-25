package eu.kanade.presentation.glass

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The canvas `Icon.dc.html` set (24x24 viewBox), by the canvas names. */
@Composable
fun GlassIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = Color.White,
    contentDescription: String? = null,
) {
    Icon(glassIcon(name), contentDescription, modifier.size(size), tint = tint)
}

fun glassIcon(name: String): ImageVector = GlassIcons.getValue(name)

val GlassIconNames: List<String> get() = GlassIcons.keys.toList()

private class P(
    val d: String,
    val fill: Boolean = false,
    val width: Float = 2f,
    val cap: StrokeCap = StrokeCap.Butt,
    val join: StrokeJoin = StrokeJoin.Miter,
)

private fun f(d: String) = P(d, fill = true)
private fun s(d: String, width: Float = 2f, cap: Boolean = false, join: Boolean = false) =
    P(d, width = width, cap = if (cap) StrokeCap.Round else StrokeCap.Butt, join = if (join) StrokeJoin.Round else StrokeJoin.Miter)

private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}" +
        "h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

private const val STAR = "M12 3.2l2.7 5.6 6.1.8-4.5 4.2 1.1 6.1L12 17l-5.4 2.9 1.1-6.1-4.5-4.2 6.1-.8z"

private val GlassIcons: Map<String, ImageVector> by lazy {
    mapOf(
        "play" to listOf(f("M7 4.5v15l12.5-7.5z")),
        "pause" to listOf(f(rect(6f, 4.5f, 4f, 15f, 1f)), f(rect(14f, 4.5f, 4f, 15f, 1f))),
        "check" to listOf(s("M4.5 12.5l5 5 10-11", 2.2f, cap = true, join = true)),
        "close" to listOf(s("M6 6l12 12M18 6L6 18", 2.2f, cap = true)),
        "download" to listOf(s("M12 3.5v11M7 10l5 5 5-5M5 20.5h14", cap = true, join = true)),
        "star" to listOf(f(STAR)),
        "starOutline" to listOf(s(STAR, 1.6f, join = true)),
        "grip" to listOf(
            f(
                listOf(9f to 6f, 15f to 6f, 9f to 12f, 15f to 12f, 9f to 18f, 15f to 18f)
                    .joinToString("") { (x, y) -> circle(x, y, 1.6f) },
            ),
        ),
        "chevDown" to listOf(s("M6.5 9.5l5.5 5.5 5.5-5.5", cap = true, join = true)),
        "chevUp" to listOf(s("M6.5 14.5L12 9l5.5 5.5", cap = true, join = true)),
        "chevLeft" to listOf(s("M14.5 6L8.5 12l6 6", cap = true, join = true)),
        "chevRight" to listOf(s("M9.5 6l6 6-6 6", cap = true, join = true)),
        "external" to listOf(s("M14 4.5h5.5V10M19.5 4.5L11 13M17 14v5.5H4.5V7H10", cap = true, join = true)),
        "plus" to listOf(s("M12 5v14M5 12h14", 2.2f, cap = true)),
        "minus" to listOf(s("M5 12h14", 2.2f, cap = true)),
        "alert" to listOf(s(circle(12f, 12f, 8.5f) + "M12 7.5v5.5M12 16.5v.01", cap = true)),
        "dot" to listOf(f(circle(12f, 12f, 5f))),
        "home" to listOf(s("M3.5 11l8.5-7 8.5 7v9a1 1 0 0 1-1 1h-5v-6h-5v6h-5a1 1 0 0 1-1-1z", join = true)),
        "compass" to listOf(s(circle(12f, 12f, 9f) + "M15.5 8.5l-2 5-5 2 2-5z", join = true)),
        "gear" to listOf(
            s(
                circle(12f, 12f, 3f) +
                    "M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1",
                cap = true,
            ),
        ),
        "search" to listOf(s(circle(11f, 11f, 7f) + "M20 20l-4-4", cap = true)),
        "list" to listOf(s("M9 6.5h11M9 12h11M9 17.5h11M4.5 6.5h.01M4.5 12h.01M4.5 17.5h.01", cap = true)),
        "crop" to listOf(s("M6.5 2.5v15h15M2.5 6.5h15v15", cap = true, join = true)),
        "book" to listOf(
            s("M12 6.5C10 5 7 4.5 3.5 5v13c3.5-.5 6.5 0 8.5 1.5 2-1.5 5-2 8.5-1.5V5C17 4.5 14 5 12 6.5zM12 6.5v13", join = true),
        ),
        "shift" to listOf(
            s(
                rect(3.5f, 5f, 7f, 14f, 1f) + rect(13.5f, 5f, 7f, 14f, 1f) + "M8 12h8M13.5 9.5L16 12l-2.5 2.5",
                cap = true,
                join = true,
            ),
        ),
        "cards" to listOf(s(rect(3f, 7f, 13f, 13f, 2f) + "M8 4h11a2 2 0 0 1 2 2v11", join = true)),
        "eye" to listOf(s("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" + circle(12f, 12f, 3f), cap = true, join = true)),
        "undo" to listOf(s("M9 14L4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3", cap = true, join = true)),
        "stats" to listOf(s("M4 19.5V14M9 19.5V9M14 19.5v-6M19 19.5V5", cap = true, join = true)),
        "bookmark" to listOf(s("M6.5 3.5h11v17l-5.5-4-5.5 4z", join = true)),
        "pencil" to listOf(s("M4 20l1-4.5L16 4.5l3.5 3.5-11 11zM14 7l3.5 3.5", join = true)),
        "speaker" to listOf(
            s("M4 9.5h3.5L12.5 5v14l-5-4.5H4zM16 9a4.5 4.5 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11", cap = true, join = true),
        ),
        "skipPrev" to listOf(f("M18 5.5v13l-9.5-6.5z"), f(rect(5.5f, 5.5f, 2.2f, 13f, 1f))),
        "skipNext" to listOf(f("M6 5.5v13l9.5-6.5z"), f(rect(16.3f, 5.5f, 2.2f, 13f, 1f))),
        "library" to listOf(
            s(rect(3.5f, 4f, 4.5f, 16f, 1f) + rect(9.5f, 4f, 4.5f, 16f, 1f) + "M15.6 5.2l3.9-1 2.9 14.6-3.9 1z", join = true),
        ),
        "folder" to listOf(
            s("M3.5 6.5a1 1 0 0 1 1-1h5l2 2.5h8a1 1 0 0 1 1 1v9.5a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1z", cap = true, join = true),
        ),
        "calendar" to listOf(s(rect(3.5f, 5f, 17f, 15.5f, 2.5f) + "M3.5 10h17M8 3v4M16 3v4", cap = true, join = true)),
        "bell" to listOf(s("M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15zM10 21h4", cap = true, join = true)),
        "key" to listOf(s(circle(8f, 15.5f, 4f) + "M11 12.5l8.5-8.5M16 7.5l2.5 2.5M13.5 10l2 2", cap = true, join = true)),
        "person" to listOf(s(circle(12f, 8f, 4f) + "M4.5 20.5c1-4 4-5.5 7.5-5.5s6.5 1.5 7.5 5.5", cap = true, join = true)),
    ).mapValues { (name, paths) ->
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { p ->
                addPath(
                    pathData = PathParser().parsePathString(p.d).toNodes(),
                    fill = if (p.fill) SolidColor(Color.Black) else null,
                    stroke = if (p.fill) null else SolidColor(Color.Black),
                    strokeLineWidth = if (p.fill) 0f else p.width,
                    strokeLineCap = p.cap,
                    strokeLineJoin = p.join,
                )
            }
        }.build()
    }
}
