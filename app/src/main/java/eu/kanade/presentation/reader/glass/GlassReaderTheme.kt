package eu.kanade.presentation.reader.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass

/** The reader's stock Material composables (seek bar, settings pages, menus) dressed in the Glass tokens. */
private val GlassReaderColors = darkColorScheme(
    primary = Glass.ice,
    onPrimary = Glass.page,
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
fun GlassReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GlassReaderColors,
        shapes = MaterialTheme.shapes.copy(extraLarge = RoundedCornerShape(Glass.r16)),
        typography = MaterialTheme.typography,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}

/** [GlassReaderTheme] when [enabled], else [content] as it is (classic). */
@Composable
fun GlassReaderTheme(enabled: Boolean, content: @Composable () -> Unit) {
    if (enabled) GlassReaderTheme(content) else content()
}

/** A4: the settings sheet's 1px white-18% line (its container is already #14171D, radius 16 under [GlassReaderTheme]). */
val GlassSheetBorder: Modifier = Modifier.border(1.dp, Glass.line, RoundedCornerShape(Glass.r16))

/** A1's page number: 12sp, white 62%, no pill. Shown only when the existing "Show page number" setting is on. */
@Composable
fun GlassPageIndicator(currentPage: String, totalPages: Int, modifier: Modifier = Modifier) {
    if (currentPage.isEmpty() || totalPages <= 0) return
    Text("$currentPage / $totalPages", modifier, color = Glass.secondary, fontSize = Glass.caption)
}
