package eu.kanade.presentation.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Room a phone page leaves at its bottom so its last row scrolls clear of [GlassPhoneBar]. */
val GlassPhoneBarRoom = 112.dp

/**
 * The phone shell's floating pill (canvas `PhoneHero-2`): the five [GlassRailItems]; white circle = [active], ice
 * dot = an area that needs attention. Solid like every glass panel (no blur).
 */
@Composable
fun GlassPhoneBar(
    active: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    dots: Set<String> = emptySet(),
) {
    Row(
        modifier
            .height(64.dp)
            .clip(CircleShape)
            .background(Glass.panel.copy(alpha = 0.94f))
            .border(1.dp, Glass.line, CircleShape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GlassRailItems.forEach { (id, label, icon) ->
            val on = id == active
            val dot = id in dots
            Box(Modifier.size(56.dp).clip(CircleShape).clickable { onSelect(id) }, contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(48.dp).then(if (on) Modifier.clip(CircleShape).background(Color.White) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    GlassIcon(
                        icon,
                        size = 24.dp,
                        tint = if (on) Color.Black else Color.White,
                        contentDescription = if (dot) "$label, needs attention" else label,
                    )
                }
                if (dot) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 13.dp, end = 13.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Glass.ice),
                    )
                }
            }
        }
    }
}
