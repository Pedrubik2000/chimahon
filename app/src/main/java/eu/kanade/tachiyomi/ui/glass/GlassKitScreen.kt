package eu.kanade.tachiyomi.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.glass.CoverCard
import eu.kanade.presentation.glass.CoverageBar
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassIconNames
import eu.kanade.presentation.glass.GlassRail
import eu.kanade.presentation.glass.GlassToggle
import eu.kanade.presentation.glass.Meter
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.util.Screen

/** Every glass component once, for a screenshot next to the canvas. Long-press the rail's bear to open. */
class GlassKitScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        var rail by remember { mutableStateOf("home") }
        var chip by remember { mutableStateOf(0) }
        var toggle by remember { mutableStateOf(true) }
        var source by remember { mutableStateOf(0) }

        Box(
            Modifier
                .fillMaxSize()
                .background(Glass.page)
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            GlassRail(active = rail, onSelect = { rail = it }, modifier = Modifier.padding(start = 20.dp, top = 24.dp))
            Column(
                Modifier
                    .padding(start = 108.dp, end = 32.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Pill("Back", onClick = { navigator.pop() }, icon = "chevLeft", small = true)

                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    listOf(Glass.hero, Glass.title, Glass.heading, Glass.label, Glass.body, Glass.caption).forEach {
                        Sample(it)
                    }
                    Text("secondary 62%", color = Glass.secondary, fontSize = Glass.body)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill("Resume", onClick = {}, primary = true, icon = "play")
                    Pill("Volumes", onClick = {})
                    Pill("On Home", onClick = {}, icon = "check")
                    Pill("Fetch 21 · 2.9 GB", onClick = {}, icon = "download", small = true)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("日本語", "Deutsch", "English").forEachIndexed { i, t ->
                        GlassChip(t, selected = chip == i, onClick = { chip = i })
                    }
                    GlassChip("Female Harem", selected = false, onClick = {}, detail = "94%")
                    GlassChip("School", selected = false, onClick = {}, detail = "90%")
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Meter(0.22f, Modifier.width(380.dp), color = Glass.ice)
                    Meter(0.84f, Modifier.width(220.dp), label = "84% known · ~560 new")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    CoverCard(null, "君のことが大大大大大好きな100人の彼女", onClick = {}, modifier = Modifier.width(110.dp), progress = 0.55f) {
                        Meter(0.91f, label = "91% known")
                    }
                    CoverCard(null, "ダンジョン飯", onClick = {}, modifier = Modifier.width(110.dp))
                    CoverCard(
                        null,
                        "Vol. 3",
                        onClick = {},
                        modifier = Modifier.width(90.dp),
                        badge = "3",
                        progress = 0.4f,
                        progressColor = Color.White,
                    ) { Meter(0.9f, label = "90") }
                }

                Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        Triple("X3DL", "All 26 · best scans · about 140 MB each", { _: Int -> true }),
                        Triple("Excnn", "Vol. 1–20 · smaller files, about 90 MB each", { n: Int -> n <= 20 }),
                        Triple("Manual search", "24 of 26 · Vol. 13–14 missing", { n: Int -> n != 13 && n != 14 }),
                    ).forEachIndexed { i, (name, have, has) ->
                        SourceCard(name, have, (1..26).map { if (has(it)) Glass.have else Glass.none }, selected = source == i) {
                            source = i
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Fill gaps from the next source", color = Color.White, fontSize = Glass.body, modifier = Modifier.weight(1f))
                        GlassToggle(toggle, { toggle = it })
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassIconNames.forEach { n ->
                        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            GlassIcon(n, size = 24.dp)
                            Text(n, color = Glass.secondary, fontSize = Glass.caption, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Sample(size: TextUnit) {
        Text("${size.value.toInt()}", color = Color.White, fontSize = size, fontWeight = FontWeight.SemiBold)
    }

    @Composable
    private fun SourceCard(name: String, have: String, cells: List<Color>, selected: Boolean, onClick: () -> Unit) {
        Column(
            Modifier
                .glassPanel(Glass.r12)
                .then(if (selected) Modifier.border(2.dp, Glass.ice, RoundedCornerShape(Glass.r12)) else Modifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(2.dp, if (selected) Glass.ice else Color.White.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Glass.ice))
                }
                Text(name, color = Color.White, fontSize = Glass.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("fast", color = Glass.secondary, fontSize = Glass.caption)
            }
            CoverageBar(cells)
            Text(have, color = Glass.secondary, fontSize = Glass.caption)
        }
    }
}
