package eu.kanade.presentation.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Room a phone page leaves at its bottom so its last row scrolls clear of [GlassPhoneBar]. */
val GlassPhoneBarRoom = 112.dp

/**
 * The phone shell's floating pill (canvas `PhoneHero-2`): the [GlassRailItems]; white circle = [active], ice
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
        // seven 48dp items + 16dp padding = 352dp: fits a 360dp phone, no gap between the touch targets
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        GlassRailItems.forEach { (id, label, icon) ->
            val on = id == active
            val dot = id in dots
            val due = (if (id == "review") glassReviewDue.intValue else 0) + (if (id == "releases") glassReleasesNew.intValue else 0)
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { onSelect(id) }, contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(42.dp).then(if (on) Modifier.clip(CircleShape).background(Color.White) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    GlassIcon(
                        icon,
                        size = 24.dp,
                        tint = if (on) Color.Black else Color.White,
                        contentDescription = listOfNotNull(label, "needs attention".takeIf { dot }, "$due ${if (id == "releases") "new" else "due"}".takeIf { due > 0 }).joinToString(", "),
                    )
                }
                dueBadge(due)?.let {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                            .heightIn(min = 18.dp)
                            .widthIn(min = 18.dp)
                            .clip(CircleShape)
                            .background(if (id == "releases") Glass.ice else Glass.green)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(it, color = Color.Black, fontSize = Glass.caption, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
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
