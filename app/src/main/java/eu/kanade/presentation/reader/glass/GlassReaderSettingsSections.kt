package eu.kanade.presentation.reader.glass

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassChip
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Pill
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.text.NumberFormat

// One composable per section of the Glass settings panel. Every preference of the classic tabs (ReadingModePage,
// GeneralSettingsPage, ColorFilterPage) is bound here directly; the classic pages stay for the classic dialog.

@Composable
private fun isWebtoon(screenModel: ReaderSettingsScreenModel): Boolean {
    val viewer by screenModel.viewerFlow.collectAsState()
    return viewer is WebtoonViewer
}

/** The small ice "This series" tag of the two per-series settings. */
@Composable
private fun SeriesTag() {
    Text(
        stringResource(MR.strings.glass_rset_scope),
        Modifier.clip(CircleShape).border(1.dp, Glass.ice, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
        color = Glass.ice,
        fontSize = 11.sp,
        maxLines = 1,
    )
}

/** The three rarer groups of Reading. */
internal enum class ReadingSub { Page, Strip, Turning }

/** Sub-chips of a tab (one wrapping row): [labels] in order, the [selected] one white. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(Modifier.padding(top = 14.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label -> GlassChip(label, selected = i == selected, onClick = { onSelect(i) }) }
    }
}

/**
 * Reading (mockup A): the per-series box (reading mode + orientation), the page layout switch, centre margin, then the rarer
 * rows behind Page / Long strip / Turning pages.
 */
@Composable
internal fun GlassReadingSection(screenModel: ReaderSettingsScreenModel, extras: GlassSettingsExtras, onClose: () -> Unit) {
    val manga by screenModel.mangaFlow.collectAsState()
    val prefs = screenModel.preferences
    val webtoon = isWebtoon(screenModel)
    val readingMode = remember(manga) { ReadingMode.fromPreference(manga?.readingMode?.toInt()) }
    val orientation = remember(manga) { ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt()) }
    val box = RoundedCornerShape(Glass.r12)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).border(1.dp, Glass.ice.copy(alpha = 0.6f), box).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassCap(stringResource(MR.strings.pref_category_reading_mode), Modifier.weight(1f))
            SeriesTag()
        }
        GlassModeTiles(readingMode, { screenModel.onChangeReadingMode(it) }) { stringResource(it.stringRes) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassCap(stringResource(MR.strings.rotation_type), Modifier.weight(1f))
            SeriesTag()
        }
        GlassPills(ReaderOrientation.entries.map { stringResource(it.stringRes) to it }, orientation) { screenModel.onChangeOrientation(it) }
        Box(Modifier.padding(bottom = 6.dp))
    }

    if (!webtoon) {
        GlassCap(stringResource(SYMR.strings.page_layout))
        GlassPrefSegmented(
            prefs.pageLayout(),
            listOf(MR.strings.glass_rset_single, MR.strings.glass_rset_double, MR.strings.glass_rset_auto).mapIndexed { i, r -> stringResource(r) to i },
        )
        Text(stringResource(MR.strings.glass_rset_auto_note), Modifier.padding(top = 6.dp), color = Glass.secondary, fontSize = Glass.caption)
        GlassCap(stringResource(SYMR.strings.pref_center_margin))
        GlassPrefPills(
            prefs.centerMarginType(),
            ReaderPreferences.CenterMarginTypes.mapIndexed { i, r -> stringResource(r) to i },
        )
    }

    var sub by remember { mutableStateOf(if (webtoon) ReadingSub.Strip else ReadingSub.Page) }
    SubChips(
        listOf(MR.strings.glass_rset_sec_page, MR.strings.glass_rset_sub_strip, MR.strings.glass_rset_sub_turning).map { stringResource(it) },
        sub.ordinal,
    ) { sub = ReadingSub.entries[it] }
    when (sub) {
        ReadingSub.Page -> GlassPagePagerRows(screenModel)
        ReadingSub.Strip -> GlassStripRows(screenModel)
        ReadingSub.Turning -> GlassTurningRows(screenModel, extras, onClose)
    }
}

/** Reading > Long strip: the webtoon / long-strip settings (padding, scale, crop, split, rotate, auto webtoon). */
@Composable
internal fun GlassStripRows(screenModel: ReaderSettingsScreenModel) {
    val prefs = screenModel.preferences
    val manga by screenModel.mangaFlow.collectAsState()
    val readingMode = remember(manga) { ReadingMode.fromPreference(manga?.readingMode?.toInt()) }
    val scaleType by prefs.webtoonScaleType().collectAsState()
    if (readingMode != ReadingMode.CONTINUOUS_VERTICAL || prefs.longStripGapSmartScale().get()) {
        GlassCap(stringResource(KMR.strings.pref_webtoon_scale_type))
        GlassPills(ReaderPreferences.WebtoonScaleType.entries.map { stringResource(it.titleRes) to it }, scaleType) { prefs.webtoonScaleType().set(it) }
    }
    val padding by prefs.webtoonSidePadding().collectAsState()
    val percent = remember { NumberFormat.getPercentInstance() }
    GlassSliderRow(
        stringResource(MR.strings.pref_webtoon_side_padding), padding,
        ReaderPreferences.WEBTOON_PADDING_MIN..ReaderPreferences.WEBTOON_PADDING_MAX,
        percent.format(padding / 100f),
    ) { prefs.webtoonSidePadding().set(it) }
    GlassPrefToggle(stringResource(MR.strings.pref_crop_borders), prefs.cropBordersWebtoon())
    GlassPrefToggle(stringResource(MR.strings.pref_crop_borders) + " (" + stringResource(MR.strings.vertical_plus_viewer) + ")", prefs.cropBordersContinuousVertical())
    val split by prefs.dualPageSplitWebtoon().collectAsState()
    GlassPrefToggle(stringResource(MR.strings.pref_dual_page_split), prefs.dualPageSplitWebtoon())
    if (split) GlassPrefToggle(stringResource(MR.strings.pref_dual_page_invert), prefs.dualPageInvertWebtoon())
    val rotate by prefs.dualPageRotateToFitWebtoon().collectAsState()
    GlassPrefToggle(stringResource(MR.strings.pref_page_rotate), prefs.dualPageRotateToFitWebtoon())
    if (rotate) GlassPrefToggle(stringResource(MR.strings.pref_page_rotate_invert), prefs.dualPageRotateToFitInvertWebtoon())
    GlassPrefToggle(stringResource(SYMR.strings.auto_webtoon_mode), prefs.useAutoWebtoon())
}

/** Reading > Page: scale, zoom start, crop, landscape zoom, pan, split / rotate wide pages, invert double. */
@Composable
internal fun GlassPagePagerRows(screenModel: ReaderSettingsScreenModel) {
    val prefs = screenModel.preferences
    val scale by prefs.imageScaleType().collectAsState()
    GlassCap(stringResource(MR.strings.pref_image_scale_type))
    GlassPrefPills(prefs.imageScaleType(), ReaderPreferences.ImageScaleType.mapIndexed { i, r -> stringResource(r) to i + 1 })
    GlassCap(stringResource(MR.strings.pref_zoom_start))
    GlassPrefPills(prefs.zoomStart(), ReaderPreferences.ZoomStart.mapIndexed { i, r -> stringResource(r) to i + 1 })
    GlassPrefToggle(stringResource(MR.strings.pref_crop_borders), prefs.cropBorders())
    if (scale in ReaderPreferences.zoomWideImagesAllowedList) {
        GlassPrefToggle(stringResource(MR.strings.pref_landscape_zoom), prefs.landscapeZoom())
    }
    GlassPrefToggle(stringResource(MR.strings.pref_navigate_pan), prefs.navigateToPan())
    val split by prefs.dualPageSplitPaged().collectAsState()
    GlassPrefToggle(stringResource(MR.strings.pref_dual_page_split), prefs.dualPageSplitPaged())
    if (split) GlassPrefToggle(stringResource(MR.strings.pref_dual_page_invert), prefs.dualPageInvertPaged())
    val rotate by prefs.dualPageRotateToFit().collectAsState()
    GlassPrefToggle(stringResource(MR.strings.pref_page_rotate), prefs.dualPageRotateToFit())
    if (rotate) GlassPrefToggle(stringResource(MR.strings.pref_page_rotate_invert), prefs.dualPageRotateToFitInvert())
    GlassPrefToggle(stringResource(SYMR.strings.invert_double_pages), prefs.invertDoublePages())
}

private val themes = listOf(
    MR.strings.black_background to 1,
    MR.strings.gray_background to 2,
    MR.strings.white_background to 0,
    MR.strings.automatic_background to 3,
)

/** Display (mockup A): General / Filters / Page bar. Filters keep the reader bars: the page beside the panel changes live. */
@Composable
internal fun GlassDisplaySection(screenModel: ReaderSettingsScreenModel) {
    var sub by remember { mutableStateOf(0) }
    SubChips(listOf(MR.strings.glass_rset_sub_general, MR.strings.glass_rset_sec_filters, MR.strings.glass_rset_sub_pagebar).map { stringResource(it) }, sub) { sub = it }
    when (sub) {
        0 -> GlassGeneralRows(screenModel)
        1 -> GlassFiltersSection(screenModel)
        else -> GlassPageBarRows(screenModel)
    }
}

/** Display > General: background, page number, fullscreen, cutout, keep screen on. */
@Composable
private fun GlassGeneralRows(screenModel: ReaderSettingsScreenModel) {
    val prefs = screenModel.preferences
    GlassCap(stringResource(MR.strings.pref_reader_theme))
    GlassPrefPills(prefs.readerTheme(), themes.map { (r, v) -> stringResource(r) to v })
    GlassPrefToggle(stringResource(MR.strings.pref_show_page_number), prefs.showPageNumber())
    GlassPrefToggle(stringResource(MR.strings.pref_fullscreen), prefs.fullscreen())
    val fullscreen by prefs.fullscreen().collectAsState()
    if (LocalActivity.current?.hasDisplayCutout() == true && fullscreen) {
        GlassPrefToggle(stringResource(MR.strings.pref_cutout_short), prefs.drawUnderCutout())
    }
    GlassPrefToggle(stringResource(MR.strings.pref_keep_screen_on), prefs.keepScreenOn())
}

/** Display > Page bar: the seekbar options. */
@Composable
private fun GlassPageBarRows(screenModel: ReaderSettingsScreenModel) {
    val prefs = screenModel.preferences
    GlassPrefToggle(stringResource(SYMR.strings.pref_force_horz_seekbar), prefs.forceHorizontalSeekbar())
    val horizontal by prefs.forceHorizontalSeekbar().collectAsState()
    if (!horizontal) {
        GlassPrefToggle(stringResource(SYMR.strings.pref_show_vert_seekbar_landscape), prefs.landscapeVerticalSeekbar())
        GlassPrefToggle(stringResource(SYMR.strings.pref_left_handed_vertical_seekbar), prefs.leftVerticalSeekbar())
    }
}

/** The page picture of one tap-zone layout: ice = next page, dark = previous, plain = the bars (inversion applied). */
@Composable
private fun TapZonePicture(nav: ViewerNavigation, rtl: Boolean, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(Glass.r8)
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth(0.62f)
                .aspectRatio(0.72f)
                .clip(shape)
                .border(if (selected) 2.dp else 1.dp, if (selected) Glass.ice else Glass.line, shape),
        ) {
            drawRect(Color(0xFFE9E7E2))
            nav.getRegions().forEach { r ->
                val color = when (r.type) {
                    // LEFT / RIGHT are screen sides: in a right-to-left pager LEFT is the next page
                    ViewerNavigation.NavigationRegion.NEXT -> Glass.ice
                    ViewerNavigation.NavigationRegion.PREV -> Color(0xFF3A3F47)
                    ViewerNavigation.NavigationRegion.LEFT -> if (rtl) Glass.ice else Color(0xFF3A3F47)
                    ViewerNavigation.NavigationRegion.RIGHT -> if (rtl) Color(0xFF3A3F47) else Glass.ice
                    else -> return@forEach
                }
                drawRect(color, Offset(r.rectF.left * size.width, r.rectF.top * size.height), Size(r.rectF.width() * size.width, r.rectF.height() * size.height))
            }
        }
        Text(label, color = if (selected) Color.White else Glass.secondary, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 13.sp)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color).border(1.dp, Glass.line, CircleShape))
        Text(label, color = Glass.secondary, fontSize = Glass.caption)
    }
}

/** Taps and gestures: the six tap-zone pictures, invert, smaller zones, long tap, zoom gestures. */
@Composable
internal fun GlassTapsSection(screenModel: ReaderSettingsScreenModel) {
    val prefs = screenModel.preferences
    val webtoon = isWebtoon(screenModel)
    val viewer by screenModel.viewerFlow.collectAsState()
    val rtl = viewer is R2LPagerViewer
    val vertical = viewer is VerticalPagerViewer
    val navPref = if (webtoon) prefs.navigationModeWebtoon() else prefs.navigationModePager()
    val invertPref = if (webtoon) prefs.webtoonNavInverted() else prefs.pagerNavInverted()
    val nav by navPref.collectAsState()
    val invert by invertPref.collectAsState()
    val smaller by prefs.smallerTapZone().collectAsState()
    val navs = remember(webtoon, vertical, invert, smaller) {
        listOf(
            // same default as PagerConfig / WebtoonConfig: L layout for webtoon and vertical paging
            if (webtoon || vertical) LNavigation() else RightAndLeftNavigation(),
            LNavigation(), KindlishNavigation(), EdgeNavigation(), RightAndLeftNavigation(), DisabledNavigation(),
        ).onEach { it.invertMode = invert }
    }
    GlassCap(stringResource(MR.strings.pref_viewer_nav))
    navs.withIndex().chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (i, n) ->
                TapZonePicture(n, rtl, stringResource(ReaderPreferences.TapZones[i]), nav == i, { navPref.set(i) }, Modifier.weight(1f))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendDot(Glass.ice, stringResource(MR.strings.glass_rset_legend_next))
        LegendDot(Color(0xFF3A3F47), stringResource(MR.strings.glass_rset_legend_prev))
        LegendDot(Color(0xFFE9E7E2), stringResource(MR.strings.glass_rset_legend_bars))
    }
    if (nav != 5) {
        GlassCap(stringResource(MR.strings.pref_read_with_tapping_inverted))
        GlassPills(ReaderPreferences.TappingInvertMode.entries.map { stringResource(it.titleRes) to it }, invert) { invertPref.set(it) }
    }
    Box(Modifier.padding(top = 6.dp))
    GlassPrefToggle(stringResource(KMR.strings.pref_viewer_nav_smaller_tap_zone), prefs.smallerTapZone())
    GlassPrefToggle(stringResource(MR.strings.pref_read_with_long_tap), prefs.readWithLongTap())
    if (webtoon) {
        GlassPrefToggle(stringResource(MR.strings.pref_double_tap_zoom), prefs.webtoonDoubleTapZoomEnabled())
        GlassPrefToggle(stringResource(KMR.strings.pref_pinch_to_zoom), prefs.webtoonPinchToZoomEnabled())
        GlassPrefToggle(stringResource(MR.strings.pref_webtoon_disable_zoom_out), prefs.webtoonDisableZoomOut())
    } else {
        GlassPrefToggle(stringResource(KMR.strings.pref_paged_disable_zoom_in), prefs.pagedDisableZoomIn())
        val noZoom by prefs.pagedDisableZoomIn().collectAsState()
        if (!noZoom) GlassPrefToggle(stringResource(MR.strings.pref_double_tap_zoom), prefs.pagedDoubleTapZoomEnabled())
    }
}

private fun colorPart(color: Int, shift: Int) = (color shr shift) and 0xFF

private fun withColorPart(current: Int, value: Int, shift: Int) = (value shl shift) or (current and (0xFF shl shift).inv())

/** Brightness, colour filter, grayscale, invert, and a reset. */
@Composable
internal fun GlassFiltersSection(screenModel: ReaderSettingsScreenModel) {
    val prefs = screenModel.preferences
    GlassPrefToggle(stringResource(MR.strings.pref_custom_brightness), prefs.customBrightness())
    val customBrightness by prefs.customBrightness().collectAsState()
    if (customBrightness) {
        val value by prefs.customBrightnessValue().collectAsState()
        GlassSliderRow(stringResource(MR.strings.pref_custom_brightness), value, -75..100, "$value") { prefs.customBrightnessValue().set(it) }
    }
    GlassDivider()
    GlassPrefToggle(stringResource(MR.strings.pref_custom_color_filter), prefs.colorFilter())
    val colorFilter by prefs.colorFilter().collectAsState()
    if (colorFilter) {
        val argb by prefs.colorFilterValue().collectAsState()
        listOf(
            MR.strings.color_filter_r_value to 16,
            MR.strings.color_filter_g_value to 8,
            MR.strings.color_filter_b_value to 0,
            MR.strings.color_filter_a_value to 24,
        ).forEach { (label, shift) ->
            val v = colorPart(argb, shift)
            GlassSliderRow(stringResource(label), v, 0..255, "$v") { new ->
                prefs.colorFilterValue().getAndSet { withColorPart(it, new, shift) }
            }
        }
        GlassCap(stringResource(MR.strings.pref_color_filter_mode))
        GlassPrefPills(prefs.colorFilterMode(), ReaderPreferences.ColorFilterMode.mapIndexed { i, p -> stringResource(p.first) to i })
    }
    GlassDivider()
    GlassPrefToggle(stringResource(MR.strings.pref_grayscale), prefs.grayscale())
    GlassPrefToggle(stringResource(MR.strings.pref_inverted_colors), prefs.invertedColors())
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Pill(stringResource(MR.strings.glass_rset_reset_filters), onClick = {
            listOf(
                prefs.customBrightness(), prefs.colorFilter(), prefs.grayscale(), prefs.invertedColors(),
            ).forEach { it.delete() }
            prefs.customBrightnessValue().delete()
            prefs.colorFilterValue().delete()
            prefs.colorFilterMode().delete()
        }, small = true)
    }
}

/** kumahon's OCR outlines and two-finger gesture. */
@Composable
internal fun GlassOcrSection(screenModel: ReaderSettingsScreenModel) {
    GlassPrefToggle(stringResource(MR.strings.pref_show_ocr_outlines), screenModel.preferences.ocrOutlineVisible())
    GlassPrefToggle(stringResource(MR.strings.pref_ocr_two_finger_gesture), screenModel.preferences.ocrTwoFingerGestureEnabled())
}

private val flashColors = listOf(
    MR.strings.pref_flash_style_black to ReaderPreferences.FlashColor.BLACK,
    MR.strings.pref_flash_style_white to ReaderPreferences.FlashColor.WHITE,
    MR.strings.pref_flash_style_white_black to ReaderPreferences.FlashColor.WHITE_BLACK,
)

/** Reading > Turning pages: chapter transition, page transitions, smooth scroll, flash on page turn, the Auto-scroll chip. */
@Composable
internal fun GlassTurningRows(screenModel: ReaderSettingsScreenModel, extras: GlassSettingsExtras, onClose: () -> Unit) {
    val prefs = screenModel.preferences
    val webtoon = isWebtoon(screenModel)
    GlassPrefToggle(stringResource(MR.strings.pref_always_show_chapter_transition), prefs.alwaysShowChapterTransition())
    GlassPrefToggle(stringResource(MR.strings.pref_page_transitions), if (webtoon) prefs.pageTransitionsWebtoon() else prefs.pageTransitionsPager())
    if (webtoon) GlassPrefToggle(stringResource(SYMR.strings.pref_smooth_scroll), prefs.smoothAutoScroll())
    extras.onAutoScroll?.let {
        Box(Modifier.padding(vertical = 6.dp)) {
            GlassChip(stringResource(MR.strings.glass_settings_auto_scroll), selected = false, onClick = { onClose(); it() }, leading = "play")
        }
    }

    GlassDivider()
    GlassPrefToggle(stringResource(MR.strings.pref_flash_page), prefs.flashOnPageChange())
    val flash by prefs.flashOnPageChange().collectAsState()
    if (flash) {
        val millis by prefs.flashDurationMillis().collectAsState()
        GlassSliderRow(
            stringResource(MR.strings.pref_flash_duration), millis / ReaderPreferences.MILLI_CONVERSION, 1..15,
            stringResource(MR.strings.pref_flash_duration_summary, millis),
        ) { prefs.flashDurationMillis().set(it * ReaderPreferences.MILLI_CONVERSION) }
        val interval by prefs.flashPageInterval().collectAsState()
        GlassSliderRow(
            stringResource(MR.strings.pref_flash_page_interval), interval, 1..10,
            pluralStringResource(MR.plurals.pref_pages, interval, interval),
        ) { prefs.flashPageInterval().set(it) }
        GlassCap(stringResource(MR.strings.pref_flash_with))
        GlassPrefPills(prefs.flashColor(), flashColors.map { (r, v) -> stringResource(r) to v })
    }
}
