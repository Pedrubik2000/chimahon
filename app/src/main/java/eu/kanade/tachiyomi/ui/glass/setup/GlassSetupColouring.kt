package eu.kanade.tachiyomi.ui.glass.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.ocr.KumapieColours
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassToggle
import eu.kanade.presentation.glass.glassPanel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

// Step 6, Word colouring (mockup A "One page"; the live preview is D's, the main server instructions C's).

private val PageBg = Color(0xFFF4F1EA)
private val PageInk = Color(0xFF111111)
private val Red = Color(0xFFD32F2F)
private val Blue = Color(0xFF1565C0)
private val Green = Color(0xFF2E7D32)
private val FieldBg = Color(0xFF0E1116)

/** "This device is the main server" is not built yet: its radio is disabled and C's instructions panel stays hidden. */
private const val MAIN_SERVER_BUILT = false

@Composable
internal fun ColouringStep(model: GlassSetupModel, phone: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // the switch and the sample line
        Column(Modifier.fillMaxWidth().glassPanel(Glass.r16).padding(if (phone) 14.dp else 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(MR.strings.glass_setup_colouring_switch), fontSize = if (phone) Glass.label else 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(MR.strings.glass_setup_colouring_switch_sub), color = Glass.secondary, fontSize = if (phone) Glass.caption else Glass.body)
                }
                GlassToggle(model.colouringOn, model::setColouring)
            }
            ColouredSample(model.colouringOn, phone, Modifier.padding(top = 14.dp))
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Legend(Red, stringResource(MR.strings.glass_setup_legend_new))
                Legend(Blue, stringResource(MR.strings.glass_setup_legend_mined))
                Legend(Green, stringResource(MR.strings.glass_setup_legend_due))
                Legend(Color.White, stringResource(MR.strings.glass_setup_legend_known))
            }
        }

        // the role of this device
        Caption(stringResource(MR.strings.glass_setup_this_device))
        if (phone) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RolePill(stringResource(MR.strings.glass_setup_role_client), selected = true, enabled = true, Modifier.weight(1f))
                RolePill(stringResource(MR.strings.glass_setup_role_server), selected = false, enabled = false, Modifier.weight(1f))
            }
            Text(stringResource(MR.strings.glass_setup_coming_soon), color = Glass.secondary, fontSize = Glass.caption)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoleCard(
                    stringResource(MR.strings.glass_setup_role_client),
                    stringResource(MR.strings.glass_setup_role_client_sub),
                    selected = true,
                    enabled = true,
                    Modifier.weight(1f),
                )
                RoleCard(
                    stringResource(MR.strings.glass_setup_role_server),
                    stringResource(MR.strings.glass_setup_coming_soon),
                    selected = false,
                    enabled = false,
                    Modifier.weight(1f),
                )
            }
        }
        // C's panel: built, shown only once the radio above can be chosen
        if (MAIN_SERVER_BUILT) MainServerPanel(model.hub, model.pairCode ?: "----")

        // the known-word sources
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { Caption(stringResource(MR.strings.glass_setup_known_from)) }
            Text(stringResource(MR.strings.glass_setup_known_from_hint), color = Glass.secondary, fontSize = Glass.body)
        }
        Column(Modifier.fillMaxWidth().glassPanel(Glass.r16)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = if (phone) 14.dp else 18.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Logo("辞", Color(0xFF2B3A55))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(MR.strings.glass_setup_src_jiten), fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(MR.strings.glass_setup_src_jiten_sub), color = Glass.secondary, fontSize = 13.sp)
                    JitenImportPanel(withTally = false)
                }
            }
            Divider()
            KumapieSource(model, phone)
            Divider()
            AnkiSource(phone)
        }
    }
}

/**
 * The sample line coloured by kumahon's own colourer ([KumapieColours.of]) when it can answer; until its dictionary is ready the
 * mockup's static colours with a "Preview" label. Off = plain text.
 */
@Composable
private fun ColouredSample(on: Boolean, phone: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // colours change after a sync or when the dictionary arrives
    DisposableEffect(Unit) {
        val off = KumapieColours.onRedraw { tick++ }
        onDispose { off() }
    }
    val live = remember(on, tick) { if (on) KumapieColours.of(context, GlassSetupLogic.SAMPLE) { tick++ } else null }
    val colours = if (!on) null else live ?: GlassSetupLogic.staticSample()
    val text = GlassSetupLogic.SAMPLE
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PageBg)) {
        Text(
            buildAnnotatedString {
                var i = 0
                while (i < text.length) {
                    val c = colours?.getOrNull(i) ?: 0
                    var j = i + 1
                    while (j < text.length && (colours?.getOrNull(j) ?: 0) == c) j++
                    withStyle(SpanStyle(color = if (c == 0) PageInk else Color(c))) { append(text.substring(i, j)) }
                    i = j
                }
            },
            Modifier.padding(horizontal = if (phone) 12.dp else 20.dp, vertical = if (phone) 12.dp else 16.dp),
            fontSize = if (phone) 17.sp else 26.sp,
            lineHeight = if (phone) 27.sp else 38.sp,
            fontWeight = FontWeight.Bold,
        )
        val label = when {
            !on -> MR.strings.glass_setup_colouring_preview_off
            live == null -> MR.strings.glass_setup_colouring_preview
            else -> null
        }
        if (label != null) {
            Text(
                stringResource(label),
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 10.dp),
                color = PageInk.copy(alpha = 0.55f),
                fontSize = Glass.caption,
            )
        }
    }
}

@Composable
private fun Legend(colour: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(colour))
        Text(text, fontSize = Glass.caption)
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, color = Glass.secondary, fontSize = Glass.caption, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.line))
}

@Composable
private fun Radio(selected: Boolean) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) Color.White else Color.White.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun RoleCard(title: String, sub: String, selected: Boolean, enabled: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(Glass.r16)
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (selected) Color.White.copy(alpha = 0.08f) else Glass.panel)
            .border(1.dp, if (selected) Color.White else Glass.line, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 2.dp)) { Radio(selected) }
        Column {
            Text(title, fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Glass.secondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun RolePill(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier) {
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .height(34.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else Glass.panel)
            .then(if (selected) Modifier else Modifier.border(1.dp, Glass.line, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Color.Black else Color.White, fontSize = Glass.body, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Tick(on: Boolean) {
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))
            .then(if (on) Modifier.background(Color.White) else Modifier.border(2.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(7.dp))),
        contentAlignment = Alignment.Center,
    ) {
        if (on) GlassIcon("check", size = 16.dp, tint = Color.Black)
    }
}

@Composable
private fun Logo(text: String, background: Color, ink: Color = Color.White) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(background), contentAlignment = Alignment.Center) {
        Text(text, color = ink, fontSize = Glass.label, fontWeight = FontWeight.ExtraBold)
    }
}

/**
 * kuma3 / AnkiDroid on this device as a known-words source: ticked while its layer is on; dimmed while no build is installed or its
 * permission is missing (the row's tap then asks it); a tap reads the Mined deck again, the box turns the layer on or off.
 */
@Composable
private fun AnkiSource(phone: Boolean) {
    val ui = rememberAnkiKnown()
    Row(
        Modifier.fillMaxWidth().then(if (ui.available) Modifier else Modifier.alpha(0.7f)).clickable { ui.tap() }
            .padding(horizontal = if (phone) 14.dp else 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.clickable(enabled = ui.available) { ui.toggle(!ui.on) }) { Tick(ui.on) }
        Logo("k3", Color(0xFF3B6E8F))
        Column(Modifier.weight(1f)) {
            Text(stringResource(MR.strings.glass_setup_src_anki), fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ui.subtitle, color = Glass.secondary, fontSize = 13.sp, maxLines = if (phone) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** kumapie as the known-words source: ticked once paired; the row opens to find it, type an address, show the code and pair. */
@Composable
private fun KumapieSource(model: GlassSetupModel, phone: Boolean) {
    var open by rememberSaveable { mutableStateOf(true) }
    var address by remember(model.hub) { mutableStateOf(model.hub) }
    val pad = if (phone) 14.dp else 18.dp
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.03f))) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = pad, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Tick(model.paired)
            Logo("kp", Glass.bear, Color.Black)
            Column(Modifier.weight(1f)) {
                Text("kumapie", fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                Text(stringResource(MR.strings.glass_setup_src_kumapie_sub), color = Glass.secondary, fontSize = 13.sp, maxLines = if (phone) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
            val state = when {
                model.paired -> stringResource(MR.strings.glass_setup_paired) to Glass.green
                model.pairCode != null -> stringResource(MR.strings.glass_setup_pairing) to Glass.ice
                else -> null
            }
            if (state != null) Text(state.first, color = state.second, fontSize = Glass.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
            GlassIcon(if (open) "chevUp" else "chevDown", size = 20.dp, tint = if (open) Color.White else Glass.secondary)
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(start = if (phone) pad else 94.dp, end = pad, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (model.paired) {
                    Text(stringResource(MR.strings.glass_setup_pair_done, model.pairedName ?: "kumapie"), fontSize = Glass.body)
                } else {
                    // the address: found on the network, or typed
                    val shape = RoundedCornerShape(Glass.r12)
                    BasicTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { model.useHub(address) }),
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth().height(48.dp).clip(shape).background(FieldBg).border(1.dp, Glass.line, shape)
                            .onFocusChanged { if (!it.isFocused) model.useHub(address) },
                        decorationBox = { inner -> Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) { inner() } },
                    )
                    Text(stringResource(MR.strings.glass_setup_type_address) + ": " + stringResource(MR.strings.glass_setup_address_hint), color = Glass.secondary, fontSize = Glass.caption)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SetupButton(
                            stringResource(if (model.scanning) MR.strings.glass_setup_finding else MR.strings.glass_setup_find_kumapie),
                            model::scan,
                            enabled = !model.scanning,
                            primary = false,
                            small = true,
                        )
                    }
                    model.found?.let { hits ->
                        when {
                            hits.isEmpty() -> Text(stringResource(MR.strings.glass_setup_find_none, chimahon.srs.HubScan.PORT), color = Glass.secondary, fontSize = Glass.body)
                            hits.size > 1 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                hits.forEach { h -> SetupButton(h, { model.useHub(h) }, primary = h == model.hub, small = true) }
                            }
                        }
                    }
                    model.pairCode?.let { code ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                code.forEach { d ->
                                    val box = RoundedCornerShape(Glass.r12)
                                    Box(
                                        Modifier.size(width = if (phone) 34.dp else 44.dp, height = if (phone) 42.dp else 48.dp).clip(box).background(FieldBg).border(1.dp, Glass.line, box),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(d.toString(), fontSize = if (phone) 20.sp else 24.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                            Text(stringResource(MR.strings.glass_setup_pair_code), Modifier.weight(1f), fontSize = if (phone) 12.sp else Glass.body, lineHeight = 20.sp)
                        }
                    }
                    model.pairNote?.let { Text(it, color = Color.White, fontSize = Glass.body) }
                    SetupButton(
                        stringResource(
                            when {
                                model.pairBusy -> MR.strings.glass_setup_working
                                model.pairCode != null -> MR.strings.glass_setup_pair_again
                                else -> MR.strings.glass_setup_pair_button
                            },
                        ),
                        { model.useHub(address); model.pair() },
                        enabled = !model.pairBusy,
                        small = true,
                    )
                }
            }
        }
    }
}

/** Mockup C's instructions for the other devices, shown when "This device is the main server" can be chosen (it cannot yet). */
@Composable
internal fun MainServerPanel(address: String, code: String) {
    Column(Modifier.fillMaxWidth().glassPanel(Glass.r16).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoBox(stringResource(MR.strings.glass_setup_server_address), address, Modifier.weight(1f))
            InfoBox(stringResource(MR.strings.glass_setup_server_code), code, Modifier.weight(1f))
        }
        Caption(stringResource(MR.strings.glass_setup_server_title))
        Text("1  " + stringResource(MR.strings.glass_setup_server_1), fontSize = Glass.body)
        Text("2  " + stringResource(MR.strings.glass_setup_server_2), fontSize = Glass.body)
        Text("3  " + stringResource(MR.strings.glass_setup_server_3), fontSize = Glass.body)
    }
}

@Composable
private fun InfoBox(label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(Glass.r12)
    Column(modifier.clip(shape).background(FieldBg).border(1.dp, Glass.line, shape).padding(14.dp)) {
        Text(label, color = Glass.secondary, fontSize = 13.sp)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
