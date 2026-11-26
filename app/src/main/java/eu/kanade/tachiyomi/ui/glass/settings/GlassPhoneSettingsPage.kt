package eu.kanade.tachiyomi.ui.glass.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassPhoneBarRoom
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.presentation.more.settings.PreferenceScreen
import eu.kanade.presentation.reader.glass.GlassReaderTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private val SectionIcons = mapOf(
    "sources" to "list",
    "downloads" to "download",
    "reader" to "book",
    "japanese" to "search",
    "mining" to "bookmark",
    "library" to "folder",
    "privacy" to "key",
    "about" to "alert",
)

/**
 * Phone Settings (canvas option A): the Accounts card and one grouped list of the other sections; a section opens
 * as its own page (Back = "Settings") with exactly what the tablet's detail pane shows for it.
 */
@Composable
fun GlassPhoneSettingsPage(navigator: Navigator) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val asked by glassSettingsSection
    LaunchedEffect(asked) {
        asked?.let { id ->
            if (Sections.any { it.id == id }) open = id
            glassSettingsSection.value = null
        }
    }
    val current = Sections.firstOrNull { it.id == open }
    if (current != null) {
        BackHandler { open = null }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill(
                stringResource(MR.strings.glass_settings_title),
                onClick = { open = null },
                modifier = Modifier.padding(top = 16.dp),
                icon = "chevLeft",
                small = true,
            )
            Text(stringResource(current.name), fontSize = Glass.heading, fontWeight = FontWeight.SemiBold)
            key(current.id) {
                GlassReaderTheme {
                    PreferenceScreen(
                        items = sectionItems(current.id, navigator),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = GlassPhoneBarRoom),
                    )
                }
            }
        }
        return
    }

    val sourceLine = sourceOrderLine()
    val accounts = Sections.first { it.id == "accounts" }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = GlassPhoneBarRoom),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(MR.strings.glass_settings_title),
            Modifier.padding(top = 16.dp, bottom = 4.dp),
            fontSize = Glass.title,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .glassPanel()
                .clickable { open = accounts.id }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(Glass.button).clip(CircleShape).background(Glass.ice),
                contentAlignment = Alignment.Center,
            ) { GlassIcon("person", size = 24.dp, tint = Color.Black) }
            SectionText(stringResource(accounts.name), stringResource(accounts.summary), Modifier.weight(1f))
            GlassIcon("chevRight", size = 20.dp, tint = Glass.secondary)
        }
        Column(Modifier.fillMaxWidth().glassPanel().padding(horizontal = 14.dp)) {
            val rest = Sections.filter { it.id != accounts.id }
            rest.forEachIndexed { i, s ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.line))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { open = s.id }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GlassIcon(SectionIcons[s.id] ?: "gear", size = 22.dp)
                    SectionText(
                        stringResource(s.name),
                        if (s.id == "sources" && sourceLine.isNotEmpty()) sourceLine else stringResource(s.summary),
                        Modifier.weight(1f),
                    )
                    GlassIcon("chevRight", size = 20.dp, tint = Glass.secondary)
                }
            }
        }
    }
}

@Composable
private fun SectionText(name: String, summary: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(summary, color = Glass.secondary, fontSize = Glass.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
