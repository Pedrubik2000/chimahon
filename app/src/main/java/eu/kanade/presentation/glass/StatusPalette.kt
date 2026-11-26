package eu.kanade.presentation.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.StatusColourSet
import eu.kanade.tachiyomi.ui.glass.title.VolState
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * How one volume state looks on its cover: the cover's [dim] (1 = full colour), a [border] (null = none), the
 * [glyph] badge top-right ([badgeFill] null = the dark scrim) and the glyph's [ink].
 */
class TileStyle(
    val dim: Float,
    val border: Color?,
    val borderWidth: Dp,
    val dashed: Boolean,
    val glyph: String?,
    val ink: Color,
    val badgeFill: Color? = null,
)

/** One status colour set. The tile rules are the mockup's (plans/mockups/kumahon-status-colours, make_covers.py). */
class StatusColours(
    private val tiles: Map<VolState, TileStyle>,
    /** A source box the source has / a run it lacks (+ its fill, dashed or not): text on the first is [haveInk]. */
    val have: Color,
    val haveInk: Color,
    val missing: Color,
    val missingFill: Color,
    val missingDashed: Boolean,
) {
    fun of(state: VolState): TileStyle = tiles.getValue(state)
}

/** The colours of the three sets, all in one place: everything that shows a volume's state reads these. */
object StatusPalette {

    private val ice = Color(0xFF9CC9FF)
    private val amber = Color(0xFFF2B45A)
    private val white = Color(0xFFF2F3F5)
    private val ink = Color(0xFF07090D)
    private val faint = Color.White.copy(alpha = 0.18f)

    private fun tiles(
        here: TileStyle,
        queued: TileStyle,
        failed: TileStyle,
        none: TileStyle,
    ) = mapOf(
        VolState.READ to TileStyle(1f, null, 0.dp, false, "check", Color.White),
        VolState.HERE to here,
        VolState.DOWNLOADING to TileStyle(0.7f, ice, 2.dp, false, "download", ice),
        VolState.QUEUED to queued,
        VolState.FAILED to failed,
        VolState.NONE to none,
    )

    /** Option D: white / grey / ice, amber only for Failed; "not asked for" = dashed + a plus. */
    private val default = StatusColours(
        tiles(
            here = TileStyle(1f, white, 2.dp, false, "phone", ink, badgeFill = white),
            queued = TileStyle(0.5f, ice, 2.dp, false, "clock", ice),
            failed = TileStyle(0.3f, amber, 2.dp, false, "bang", ink, badgeFill = amber),
            none = TileStyle(0.22f, Color.White.copy(alpha = 0.45f), 1.5.dp, true, "plus", Color(0xFFC9CDD4)),
        ),
        have = Color(0xFFE6E8EC),
        haveInk = ink,
        missing = Color.White.copy(alpha = 0.45f),
        missingFill = Color.Transparent,
        missingDashed = true,
    )

    /** Option A: green = on device / available, red = failed / missing. */
    private val greenRed = StatusColours(
        tiles(
            here = TileStyle(1f, Color(0xFF4FAF6E), 3.dp, false, "phone", Color(0xFF07120B), badgeFill = Color(0xFF4FAF6E)),
            queued = TileStyle(0.5f, ice, 2.dp, true, "clock", ice),
            failed = TileStyle(0.3f, Color(0xFFE0626B), 2.dp, false, "bang", Color(0xFFFF8A92)),
            none = TileStyle(0.2f, faint, 1.5.dp, false, null, Color.White),
        ),
        have = Color(0xFF4FAF6E),
        haveInk = Color(0xFF07120B),
        missing = Color(0xFFE0626B),
        missingFill = Color.Transparent,
        missingDashed = false,
    )

    /** Option B: ice = there / on its way, amber = missing or failed; no red or green. */
    private val iceAmber = StatusColours(
        tiles(
            here = TileStyle(1f, ice, 3.dp, false, "phone", ink, badgeFill = ice),
            queued = TileStyle(0.5f, ice, 2.dp, true, "clock", ice),
            failed = TileStyle(0.3f, amber, 2.dp, false, "bang", amber),
            none = TileStyle(0.2f, faint, 1.5.dp, false, null, Color.White),
        ),
        have = ice,
        haveInk = ink,
        missing = amber,
        missingFill = amber.copy(alpha = 0.10f),
        missingDashed = true,
    )

    fun of(set: StatusColourSet): StatusColours = when (set) {
        StatusColourSet.DEFAULT -> default
        StatusColourSet.GREEN_RED -> greenRed
        StatusColourSet.ICE_AMBER -> iceAmber
    }

    /** The chosen set (Settings, or the setup's storage step); recomposes when it changes. */
    @Composable
    fun current(): StatusColours {
        val pref = remember { Injekt.get<UiPreferences>().glassStatusColours() }
        val set by pref.collectAsState()
        return of(set)
    }
}
