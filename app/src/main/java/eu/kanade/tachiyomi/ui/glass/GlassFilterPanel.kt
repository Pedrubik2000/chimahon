package eu.kanade.tachiyomi.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Canvas `Browse-2` filter sidebar, shared by Discover Browse and Library.
 * Wide (≥ 900dp): the 264dp panel column right of the rail, heading "Filters" + [headerAction], [panel] below,
 * [content] to its right. Narrow: no column; [content] gets a slot (a "Filters ▾" pill that expands [panel]) to put
 * above its grid. [content] pads its own top and end; the start is set here.
 */
@Composable
fun GlassFilterScaffold(
    panel: @Composable () -> Unit,
    headerAction: @Composable () -> Unit = {},
    content: @Composable (filters: @Composable () -> Unit) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 900.dp) {
            FilterSidebar(panel, headerAction, Modifier.padding(start = 100.dp).width(264.dp).fillMaxHeight())
            Box(Modifier.fillMaxSize().padding(start = 396.dp)) { content {} }
        } else {
            Box(Modifier.fillMaxSize().padding(start = 108.dp)) { content { FilterSection(panel, headerAction) } }
        }
    }
}

@Composable
private fun FilterSidebar(panel: @Composable () -> Unit, headerAction: @Composable () -> Unit, modifier: Modifier) {
    Row(modifier) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Glass.panel)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(MR.strings.glass_discover_filters),
                    fontSize = Glass.heading,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                headerAction()
            }
            panel()
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Glass.line))
    }
}

/** Narrow widths: "Filters ▾" pill; open = the groups in a panel-coloured box that scrolls on its own. */
@Composable
private fun FilterSection(panel: @Composable () -> Unit, headerAction: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip(
                stringResource(MR.strings.glass_discover_filters),
                selected = false,
                onClick = { open = !open },
                icon = if (open) "chevUp" else "chevDown",
            )
            headerAction()
        }
        if (open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(Glass.r12))
                    .background(Glass.panel)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { panel() }
        }
    }
}

/** Small caps label + wrapping chips. */
@Composable
fun FilterGroup(name: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, color = Glass.secondary, fontSize = Glass.caption, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

@Composable
fun GlassSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    Row(
        modifier
            .height(Glass.button)
            .clip(CircleShape)
            .background(Glass.panel)
            .border(1.dp, Glass.line, CircleShape)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassIcon("search", size = 18.dp, tint = Glass.secondary)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = Glass.secondary, fontSize = Glass.label, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = Glass.label),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            GlassIcon("close", size = 18.dp, modifier = Modifier.clip(CircleShape).clickable { onValueChange("") })
        }
    }
}
