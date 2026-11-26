package eu.kanade.presentation.reader.glass

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
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

/** Reading mode tiles, orientation, page layout, centre margin. */
@Composable
internal fun GlassModeSection(screenModel: ReaderSettingsScreenModel) {
    val manga by screenModel.mangaFlow.collectAsState()
    val prefs = screenModel.preferences
    val readingMode = remember(manga) { ReadingMode.fromPreference(manga?.readingMode?.toInt()) }
    GlassCap(stringResource(MR.strings.pref_category_reading_mode) + " · " + stringResource(MR.strings.glass_rset_scope), Modifier.fillMaxWidth())
    GlassModeTiles(readingMode, { screenModel.onChangeReadingMode(it) }) { stringResource(it.stringRes) }

    if (!isWebtoon(screenModel)) {
        GlassCap(stringResource(SYMR.strings.page_layout))
        GlassPrefSegmented(
            prefs.pageLayout(),
            ReaderPreferences.PageLayouts.mapIndexed { i, r -> stringResource(r) to i },
        )
        GlassCap(stringResource(SYMR.strings.pref_center_margin))
        GlassPrefPills(
            prefs.centerMarginType(),
            ReaderPreferences.CenterMarginTypes.mapIndexed { i, r -> stringResource(r) to i },
        )
    }

    val orientation = remember(manga) { ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt()) }
    GlassCap(stringResource(MR.strings.rotation_type) + " · " + stringResource(MR.strings.glass_rset_scope))
    GlassPills(ReaderOrientation.entries.map { stringResource(it.stringRes) to it }, orientation) { screenModel.onChangeOrientation(it) }
}

/** Scale, zoom, cropping, double pages: what is shown per page (pager or webtoon settings). */
@Composable
internal fun GlassPageSection(screenModel: ReaderSettingsScreenModel) {
    val prefs = screenModel.preferences
    if (isWebtoon(screenModel)) {
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
    } else {
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
}

private val themes = listOf(
    MR.strings.black_background to 1,
    MR.strings.gray_background to 2,
    MR.strings.white_background to 0,
    MR.strings.automatic_background to 3,
)

/** Background, page number, fullscreen, cutout, keep screen on. */
@Composable
internal fun GlassDisplaySection(screenModel: ReaderSettingsScreenModel) {
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

/** Tap zones and gestures. */
@Composable
internal fun GlassTapsSection(screenModel: ReaderSettingsScreenModel) {
    val prefs = screenModel.preferences
    val webtoon = isWebtoon(screenModel)
    val navPref = if (webtoon) prefs.navigationModeWebtoon() else prefs.navigationModePager()
    val invertPref = if (webtoon) prefs.webtoonNavInverted() else prefs.pagerNavInverted()
    val nav by navPref.collectAsState()
    GlassCap(stringResource(MR.strings.pref_viewer_nav))
    GlassPrefPills(navPref, ReaderPreferences.TapZones.mapIndexed { i, r -> stringResource(r) to i })
    if (nav != 5) {
        GlassCap(stringResource(MR.strings.pref_read_with_tapping_inverted))
        GlassPrefPills(invertPref, ReaderPreferences.TappingInvertMode.entries.map { stringResource(it.titleRes) to it })
    }
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

/** Seekbar, chapter transition, page flash, auto webtoon, page transitions, smooth scroll. */
@Composable
internal fun GlassMoreSection(screenModel: ReaderSettingsScreenModel) {
    val prefs = screenModel.preferences
    val webtoon = isWebtoon(screenModel)
    GlassPrefToggle(stringResource(SYMR.strings.pref_force_horz_seekbar), prefs.forceHorizontalSeekbar())
    val horizontal by prefs.forceHorizontalSeekbar().collectAsState()
    if (!horizontal) {
        GlassPrefToggle(stringResource(SYMR.strings.pref_show_vert_seekbar_landscape), prefs.landscapeVerticalSeekbar())
        GlassPrefToggle(stringResource(SYMR.strings.pref_left_handed_vertical_seekbar), prefs.leftVerticalSeekbar())
    }
    GlassPrefToggle(stringResource(MR.strings.pref_always_show_chapter_transition), prefs.alwaysShowChapterTransition())
    GlassPrefToggle(stringResource(SYMR.strings.auto_webtoon_mode), prefs.useAutoWebtoon())
    GlassPrefToggle(stringResource(MR.strings.pref_page_transitions), if (webtoon) prefs.pageTransitionsWebtoon() else prefs.pageTransitionsPager())
    if (webtoon) GlassPrefToggle(stringResource(SYMR.strings.pref_smooth_scroll), prefs.smoothAutoScroll())

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
