package eu.kanade.tachiyomi.ui.glass.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.ocr.KumapieColours
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.GlassToggle
import eu.kanade.presentation.glass.glassPanel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

// Steps 6 (Word colouring) and 7 (Known words) of setup mockup B: one step per screen, a manga page with vertical bubbles at the right.

// The manga page stands for a page of a book: its paper is the one place the setup is not dark. The three word colours are the reader's
// (KumapieColours; GlassSetupLogic.staticSample has the same three).
private val PageBg = Color(0xFFF7F5F0)
private val PageInk = Color(0xFF111111)
private val Red = Color(0xFFD32F2F)
private val Blue = Color(0xFF1565C0)
private val Green = Color(0xFF2E7D32)
private val Hatching = Color(0xFFD9D6CF)

/** "This device is the main server" is not built yet: its radio is disabled and C's instructions panel stays hidden. */
private const val MAIN_SERVER_BUILT = false

/** Step 6: the on/off switch, this device's role, and (when the right side has no room) the preview page below. */
@Composable
internal fun ColouringStep(model: GlassSetupModel, phone: Boolean, wide: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.fillMaxWidth().glassPanel(Glass.r16).padding(if (phone) 16.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(MR.strings.glass_setup_colouring_switch), fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                Text(stringResource(MR.strings.glass_setup_colouring_switch_sub), color = Glass.secondary, fontSize = Glass.body)
            }
            GlassToggle(model.colouringOn, model::setColouring)
        }

        // the role of this device
        Caption(stringResource(MR.strings.glass_setup_this_device), Modifier.padding(top = 8.dp))
        val roles = listOf<@Composable (Modifier) -> Unit>(
            { m -> RoleCard(stringResource(MR.strings.glass_setup_role_client), stringResource(MR.strings.glass_setup_role_client_sub), selected = true, enabled = true, m) },
            { m -> RoleCard(stringResource(MR.strings.glass_setup_role_server), stringResource(MR.strings.glass_setup_coming_soon), selected = false, enabled = false, m) },
        )
        if (phone) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { roles.forEach { it(Modifier.fillMaxWidth()) } }
        } else {
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                roles.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        // C's panel: built, shown only once the radio above can be chosen
        if (MAIN_SERVER_BUILT) MainServerPanel(model.hub, model.pairCode ?: "----")
        Text(stringResource(MR.strings.glass_setup_next_known), color = Glass.secondary, fontSize = Glass.body)

        if (!wide) MangaPreview(model.colouringOn, known = false, Modifier.fillMaxWidth().widthIn(max = 600.dp))
    }
}

/** Step 7: the Jiten list (slider, sets, Import), kumapie and kuma3 / AnkiDroid. */
@Composable
internal fun KnownWordsStep(model: GlassSetupModel, phone: Boolean, wide: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().glassPanel(Glass.r16).padding(if (phone) 16.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Logo("辞", Color.White)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(MR.strings.glass_setup_src_jiten), fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(MR.strings.glass_setup_src_jiten_sub), color = Glass.secondary, fontSize = Glass.body)
                }
            }
            JitenImportPanel(withTally = false)
        }
        Column(Modifier.fillMaxWidth().glassPanel(Glass.r16)) {
            KumapieSource(model, phone)
            Divider()
            AnkiSource(phone)
        }
        if (!wide) MangaPreview(model.colouringOn, known = true, Modifier.fillMaxWidth().widthIn(max = 600.dp))
    }
}

private val Ellipse = GenericShape { size, _ -> addOval(Rect(0f, 0f, size.width, size.height)) }

/** One vertical column of a bubble (read top to bottom, columns right to left): each character with its colour (0 = ink). */
private class Col(val chars: List<Pair<Char, Int>>)

/**
 * A manga page with four bubbles in vertical text, coloured by kumahon's own colourer ([KumapieColours.of]) when it can answer; until its
 * dictionary is ready the three static colours of the mockup with a "Preview" caption. Off = plain ink. Drawn for 600dp, scaled to its width.
 */
@Composable
internal fun MangaPreview(on: Boolean, known: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // colours change after a sync or when the dictionary arrives
    DisposableEffect(Unit) {
        val off = KumapieColours.onRedraw { tick++ }
        onDispose { off() }
    }
    val sample = GlassSetupLogic.SAMPLE
    val live = remember(on, tick) { if (on) KumapieColours.of(context, sample) { tick++ } else null }
    val colours = if (!on) null else live ?: GlassSetupLogic.staticSample()
    // the sample without its 「」, cut into the two bubbles' four columns
    fun col(from: Int, to: Int) = Col((from until to).map { sample[it + 1] to (colours?.getOrNull(it + 1) ?: 0) })
    val cols1 = listOf(col(0, 7), col(7, 14)) // この魔法学校に / 入学するには、
    val cols2 = listOf(col(14, 19), col(19, sample.length - 2)) // 試験に合格 / する必要がある
    fun plain(s: String) = Col(s.map { it to 0 })
    val label = when {
        !on -> MR.strings.glass_setup_colouring_preview_off
        known -> MR.strings.glass_setup_known_preview
        live == null -> MR.strings.glass_setup_colouring_preview
        else -> null
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (label != null) Caption(stringResource(label).uppercase())
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(600f / 690f).clip(RoundedCornerShape(Glass.r12)).background(PageBg)) {
            val s = maxWidth.value / 600f
            fun d(v: Int) = (v * s).dp
            Frame(d(19), d(19), d(562), d(267), s, hatchWidth = 0.45f, hatchHeight = 0.6f)
            Frame(d(19), d(306), d(267), d(292), s, hatchWidth = 1f, hatchHeight = 0.35f)
            Frame(d(306), d(306), d(275), d(292), s, hatchWidth = 0f, hatchHeight = 0f)
            Bubble(d(464), d(25), d(104), d(273), s, 26, cols1)
            Bubble(d(366), d(53), d(104), d(273), s, 26, cols2)
            Bubble(d(75), d(342), d(72), d(187), s, 23, listOf(plain("本当に⋮？")))
            Bubble(d(416), d(353), d(109), d(187), s, 23, listOf(plain("明日から"), plain("だぞ")))
            val legendSize = (14 * s).coerceAtLeast(11f).sp
            FlowRow(
                Modifier.align(Alignment.BottomStart).padding(start = d(23), end = d(23), bottom = d(22)),
                horizontalArrangement = Arrangement.spacedBy(d(22)),
                verticalArrangement = Arrangement.spacedBy(d(4)),
            ) {
                Legend("試験", Red, stringResource(MR.strings.glass_setup_legend_new), legendSize)
                Legend("合格", Green, stringResource(MR.strings.glass_setup_legend_due), legendSize)
                Legend("入学", Blue, stringResource(MR.strings.glass_setup_legend_mined), legendSize)
                Legend("学校", PageInk, stringResource(MR.strings.glass_setup_legend_known), legendSize)
            }
        }
    }
}

@Composable
private fun Frame(
    x: Dp,
    y: Dp,
    w: Dp,
    h: Dp,
    s: Float,
    hatchWidth: Float,
    hatchHeight: Float,
) {
    Box(Modifier.offset(x, y).size(w, h).background(Color.White).border(BorderStroke((3 * s).dp, PageInk)).clipToBounds()) {
        if (hatchHeight > 0f) {
            // the diagonal shading: lines every 9 units at 135 degrees, in the box's bottom part
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(hatchWidth).fillMaxHeight(hatchHeight)
                    .drawBehind {
                        val step = 9 * s * density
                        var x0 = 0f
                        while (x0 < size.width + size.height) {
                            drawLine(Hatching, Offset(x0, 0f), Offset(x0 - size.height, size.height), strokeWidth = 2 * s * density)
                            x0 += step
                        }
                    },
            )
        }
    }
}

@Composable
private fun Bubble(
    x: Dp,
    y: Dp,
    w: Dp,
    h: Dp,
    s: Float,
    size: Int,
    cols: List<Col>,
) {
    val fs = (size * s).sp
    val cell = with(androidx.compose.ui.platform.LocalDensity.current) { fs.toDp() } * 1.1f
    Box(
        Modifier.offset(x, y).size(w, h).clip(Ellipse).background(Color.White).border(BorderStroke((2.5f * s).dp, PageInk), Ellipse),
        contentAlignment = Alignment.Center,
    ) {
        // vertical text: the first column is the rightmost
        Row(horizontalArrangement = Arrangement.spacedBy((size * 0.3f * s).dp)) {
            cols.asReversed().forEach { c ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    c.chars.forEach { (ch, colour) ->
                        // one fixed cell per character: the font's own line height would spread them
                        Box(Modifier.height(cell), contentAlignment = Alignment.Center) {
                            Text(
                                ch.toString(),
                                Modifier.wrapContentHeight(unbounded = true),
                                color = if (colour == 0) PageInk else Color(colour),
                                fontSize = fs,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(word: String, colour: Color, text: String, size: TextUnit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(word, color = colour, fontSize = size, fontWeight = FontWeight.Bold)
        Text(text.lowercase(), color = PageInk, fontSize = size)
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Glass.secondary, fontSize = Glass.caption, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
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
            Text(sub, color = Glass.secondary, fontSize = Glass.body, lineHeight = 20.sp)
        }
    }
}

/** A 40dp source logo: the mark on the page colour, 12dp corners, the 18% line. */
@Composable
private fun Logo(text: String, ink: Color) {
    val shape = RoundedCornerShape(Glass.r12)
    Box(Modifier.size(Glass.chip).clip(shape).background(Glass.page).border(1.dp, Glass.line, shape), contentAlignment = Alignment.Center) {
        Text(text, color = ink, fontSize = Glass.label, fontWeight = FontWeight.ExtraBold)
    }
}

/**
 * kuma3 / AnkiDroid on this device as a known-words source: the switch is its layer; dimmed while no build is installed or its
 * permission is missing (the row's tap then asks it); a tap on the row reads the Mined deck again.
 */
@Composable
private fun AnkiSource(phone: Boolean) {
    val ui = rememberAnkiKnown()
    Row(
        Modifier.fillMaxWidth().then(if (ui.available) Modifier else Modifier.alpha(0.6f)).clickable { ui.tap() }
            .padding(horizontal = if (phone) 16.dp else 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Logo("k3", Color.White)
        Column(Modifier.weight(1f)) {
            Text(stringResource(MR.strings.glass_setup_src_anki), fontSize = Glass.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ui.subtitle, color = Glass.secondary, fontSize = Glass.body, maxLines = if (phone) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
        GlassToggle(ui.on, { if (ui.available) ui.toggle(it) else ui.tap() })
    }
}

/**
 * kumapie as the known-words source: its switch is on once paired (switching it on opens the row); the row opens to find it,
 * type an address, show the code and pair.
 */
@Composable
private fun KumapieSource(model: GlassSetupModel, phone: Boolean) {
    var open by rememberSaveable { mutableStateOf(true) }
    var address by remember(model.hub) { mutableStateOf(model.hub) }
    val pad = if (phone) 16.dp else 20.dp
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = pad, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Logo("kp", Glass.bear)
            Column(Modifier.weight(1f)) {
                Text("kumapie", fontSize = Glass.label, fontWeight = FontWeight.SemiBold)
                Text(stringResource(MR.strings.glass_setup_src_kumapie_sub), color = Glass.secondary, fontSize = Glass.body, maxLines = if (phone) 3 else 1, overflow = TextOverflow.Ellipsis)
            }
            val state = when {
                model.paired -> stringResource(MR.strings.glass_setup_paired) to Color.White
                model.pairCode != null -> stringResource(MR.strings.glass_setup_pairing) to Glass.ice
                else -> null
            }
            // a phone has no room for the word next to the switch
            if (state != null && !(phone && model.paired)) Text(state.first, color = state.second, fontSize = Glass.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
            GlassIcon(if (open) "chevUp" else "chevDown", size = 20.dp, tint = if (open) Color.White else Glass.secondary)
            GlassToggle(model.paired, { if (it) open = true })
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(start = if (phone) pad else 74.dp, end = pad, bottom = 16.dp),
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
                        textStyle = TextStyle(color = Color.White, fontSize = Glass.label),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth().height(Glass.button).clip(shape).background(Glass.page).border(1.dp, Glass.line, shape)
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
                        )
                    }
                    model.found?.let { hits ->
                        when {
                            hits.isEmpty() -> Text(stringResource(MR.strings.glass_setup_find_none, chimahon.srs.HubScan.PORT), color = Glass.secondary, fontSize = Glass.body)
                            hits.size > 1 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                hits.forEach { h -> GlassChip(h, selected = h == model.hub, onClick = { model.useHub(h) }) }
                            }
                        }
                    }
                    model.pairCode?.let { code ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                code.forEach { d ->
                                    val box = RoundedCornerShape(Glass.r12)
                                    Box(
                                        Modifier.size(width = if (phone) 36.dp else 44.dp, height = Glass.button).clip(box).background(Glass.page).border(1.dp, Glass.line, box),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(d.toString(), fontSize = Glass.heading, fontWeight = FontWeight.Bold) }
                                }
                            }
                            Text(stringResource(MR.strings.glass_setup_pair_code), Modifier.weight(1f), fontSize = Glass.body, lineHeight = 20.sp)
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
    Column(modifier.clip(shape).background(Glass.page).border(1.dp, Glass.line, shape).padding(14.dp)) {
        Text(label, color = Glass.secondary, fontSize = Glass.body)
        Text(value, fontSize = Glass.heading, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
