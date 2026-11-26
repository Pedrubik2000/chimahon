package eu.kanade.presentation.reader.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassMaterialTheme

/** The reader's stock Material composables (seek bar, settings pages, menus) dressed in the Glass scheme (shared: GlassMaterialTheme). */
@Composable
fun GlassReaderTheme(content: @Composable () -> Unit) {
    GlassMaterialTheme {
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
