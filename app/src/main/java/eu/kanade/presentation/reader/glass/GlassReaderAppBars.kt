package eu.kanade.presentation.reader.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share

import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassRoundButton
import eu.kanade.presentation.reader.appbars.ExhUtils
import eu.kanade.presentation.reader.appbars.NavBarType
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOcrSource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

private val slide = tween<IntOffset>(200)
private val fade = tween<Float>(150)
internal val barColor = Glass.panel.copy(alpha = 0.94f)

/**
 * A3 (pref glassUi): the reader controls in the Glass look. Same actions as [eu.kanade.presentation.reader.appbars.ReaderAppBars]:
 * the six canvas tools in the row, reading mode / orientation / web view / browser / share behind "More".
 */
@Composable
fun GlassReaderAppBars(
    visible: Boolean,
    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    pageBookmarks: Set<Int>,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
    viewer: Viewer?,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    onClickMangaStats: (() -> Unit)?,
    isExhToolsVisible: Boolean,
    onSetExhUtilsVisibility: (Boolean) -> Unit,
    isAutoScroll: Boolean,
    isAutoScrollEnabled: Boolean,
    onToggleAutoscroll: (Boolean) -> Unit,
    autoScrollFrequency: String,
    onSetAutoScrollFrequency: (String) -> Unit,
    onClickAutoScrollHelp: () -> Unit,
    onClickRetryAll: () -> Unit,
    onClickRetryAllHelp: () -> Unit,
    onClickBoostPage: () -> Unit,
    onClickBoostPageHelp: () -> Unit,
    navBarType: NavBarType,
    currentPageText: String,
    currentReadingMode: ReadingMode,
    dualPageSplitEnabled: Boolean,
    doublePages: Boolean,
    onClickChapterList: () -> Unit,
    onClickPageLayout: () -> Unit,
    onClickShiftPage: () -> Unit,
    ocrEnabled: Boolean,
    ocrLoading: Boolean,
    ocrSource: ReaderOcrSource,
    onToggleOcr: (() -> Unit)?,
    onClickOcrSource: () -> Unit,
    ocrProgress: String?,
    pagePanel: (@Composable () -> Unit)?,
) {
    val isRtl = viewer is R2LPagerViewer
    // The vertical seek bar modes keep the stock navigator; the bottom one is the Glass page bar.
    val navigator: @Composable (Boolean) -> Unit = { vertical ->
        if (vertical) {
            GlassReaderTheme {
                ChapterNavigator(
                    isRtl = isRtl,
                    isVerticalSlider = true,
                    onNextChapter = onNextChapter,
                    enabledNext = enabledNext,
                    onPreviousChapter = onPreviousChapter,
                    enabledPrevious = enabledPrevious,
                    currentPage = currentPage,
                    currentPageText = currentPageText,
                    totalPages = totalPages,
                    onPageIndexChange = onPageIndexChange,
                )
            }
        } else {
            GlassPageBar(
                isRtl = isRtl,
                onNextChapter = onNextChapter,
                enabledNext = enabledNext,
                onPreviousChapter = onPreviousChapter,
                enabledPrevious = enabledPrevious,
                currentPage = currentPage,
                totalPages = totalPages,
                chapterTitle = chapterTitle,
                onPageIndexChange = onPageIndexChange,
                viewer = viewer,
                currentPageText = currentPageText,
                bookmarkedPages = pageBookmarks,
            )
        }
    }

    // The header (with the words panel) is measured above the bottom bar, so it can never push the bar off screen.
    var barPx by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxHeight()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(slide) { -it } + fadeIn(fade),
            exit = slideOutVertically(slide) { -it } + fadeOut(fade),
            modifier = Modifier.layout { measurable, c ->
                val p = measurable.measure(c.copy(maxHeight = (c.maxHeight - barPx).coerceAtLeast(0)))
                layout(p.width, p.height) { p.place(0, 0) }
            },
        ) {
            Column {
                GlassReaderHeader(
                    mangaTitle = mangaTitle,
                    chapterTitle = chapterTitle,
                    navigateUp = navigateUp,
                    onClickTitle = onClickTopAppBar,
                    bookmarked = bookmarked,
                    onToggleBookmarked = onToggleBookmarked,
                    ocrEnabled = ocrEnabled,
                    ocrLoading = ocrLoading,
                    ocrSource = ocrSource,
                    onToggleOcr = onToggleOcr,
                    onClickOcrSource = onClickOcrSource,
                    ocrProgress = ocrProgress,
                    tools = { modifier, narrow ->
                        GlassReaderTools(
                            modifier = modifier,
                            narrow = narrow,
                            readingMode = readingMode,
                            onClickReadingMode = onClickReadingMode,
                            orientation = orientation,
                            onClickOrientation = onClickOrientation,
                            cropEnabled = cropEnabled,
                            onClickCropBorder = onClickCropBorder,
                            onClickSettings = onClickSettings,
                            onClickMangaStats = onClickMangaStats,
                            currentReadingMode = currentReadingMode,
                            dualPageSplitEnabled = dualPageSplitEnabled,
                            doublePages = doublePages,
                            onClickChapterList = onClickChapterList,
                            onClickWebView = onOpenInWebView,
                            onClickBrowser = onOpenInBrowser,
                            onClickShare = onShare,
                            onClickPageLayout = onClickPageLayout,
                            onClickShiftPage = onClickShiftPage,
                            onShowExhTools = { onSetExhUtilsVisibility(true) },
                        )
                    },
                )
                // The auto-scroll / retry strip only shows once opened from More (no full-width band otherwise).
                if (isExhToolsVisible) GlassReaderTheme {
                    ExhUtils(
                        isVisible = isExhToolsVisible,
                        onSetExhUtilsVisibility = onSetExhUtilsVisibility,
                        backgroundColor = barColor,
                        isAutoScroll = isAutoScroll,
                        isAutoScrollEnabled = isAutoScrollEnabled,
                        onToggleAutoscroll = onToggleAutoscroll,
                        autoScrollFrequency = autoScrollFrequency,
                        onSetAutoScrollFrequency = onSetAutoScrollFrequency,
                        onClickAutoScrollHelp = onClickAutoScrollHelp,
                        onClickRetryAll = onClickRetryAll,
                        onClickRetryAllHelp = onClickRetryAllHelp,
                        onClickBoostPage = onClickBoostPage,
                        onClickBoostPageHelp = onClickBoostPageHelp,
                    )
                }
                if (pagePanel != null) {
                    Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
                        pagePanel()
                    }
                }
            }
        }

        when (navBarType) {
            NavBarType.VerticalLeft, NavBarType.VerticalRight -> {
                val left = navBarType == NavBarType.VerticalLeft
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInHorizontally(slide) { if (left) -it else it } + fadeIn(fade),
                    exit = slideOutHorizontally(slide) { if (left) -it else it } + fadeOut(fade),
                    modifier = Modifier.weight(1f).align(if (left) Alignment.Start else Alignment.End),
                ) { navigator(true) }
            }
            NavBarType.Bottom -> Spacer(Modifier.weight(1f))
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(slide) { it } + fadeIn(fade),
            exit = slideOutVertically(slide) { it } + fadeOut(fade),
            modifier = Modifier.onSizeChanged { barPx = it.height },
        ) {
            if (navBarType == NavBarType.Bottom) navigator(false)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlassReaderHeader(
    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTitle: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    ocrEnabled: Boolean,
    ocrLoading: Boolean,
    ocrSource: ReaderOcrSource,
    onToggleOcr: (() -> Unit)?,
    onClickOcrSource: () -> Unit,
    ocrProgress: String?,
    tools: @Composable (Modifier, Boolean) -> Unit,
) {
    // The mockup's floating header panel: 64dp, 16dp radius, 44dp outlined round buttons. The tool buttons sit beside
    // the title on a wide screen, on a second line on a phone.
    val narrow = LocalConfiguration.current.screenWidthDp < 640
    val shape = RoundedCornerShape(Glass.r16)
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Column(Modifier.fillMaxWidth().clip(shape).background(barColor).border(1.dp, Glass.line, shape)) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassRoundButton("chevLeft", navigateUp, glassRound, description = stringResource(MR.strings.action_bar_up_description), outlined = true, iconSize = 20.dp)
                Column(Modifier.weight(1f).clickable(onClick = onClickTitle)) {
                    Text(
                        mangaTitle.orEmpty(),
                        color = Color.White,
                        fontFamily = FontFamily.Serif,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    chapterTitle?.let {
                        Text(it, color = Glass.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!narrow) {
                    tools(Modifier, false)
                    Box(Modifier.width(1.dp).height(32.dp).background(Glass.line))
                }
                onToggleOcr?.let { toggle ->
                    // One pill, two touch targets: "OCR" turns it on or off, "Auto ▾" opens the source panel.
                    val enabled = ocrEnabled || !ocrLoading
                    val ink = if (ocrEnabled) Glass.ice else Color.White
                    Row(
                        Modifier
                            .height(36.dp)
                            .clip(CircleShape)
                            .border(1.dp, if (ocrEnabled) Glass.ice else Glass.line, CircleShape),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.fillMaxHeight().clickable(enabled = enabled, onClick = toggle).padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (ocrProgress != null) CircularProgressIndicator(Modifier.size(12.dp), color = Glass.ice, strokeWidth = 2.dp)
                            Text(
                                stringResource(MR.strings.action_ocr) + ocrProgress?.let { " $it" }.orEmpty(),
                                color = ink,
                                fontSize = 14.sp,
                                maxLines = 1,
                            )
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Glass.line))
                        Row(
                            Modifier.fillMaxHeight().clickable(onClick = onClickOcrSource).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(ocrSource.shortLabel()) + " ▾", color = Color.White, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                }
                GlassRoundButton(
                    if (bookmarked) "bookmarkFilled" else "bookmark",
                    onToggleBookmarked,
                    glassRound,
                    description = stringResource(if (bookmarked) MR.strings.action_remove_bookmark else MR.strings.action_bookmark),
                    outlined = true,
                    tint = if (bookmarked) Glass.ice else Color.White,
                    iconSize = 20.dp,
                )
            }
            if (narrow) tools(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp), true)
        }
    }
}

/** The mockup's `.ibtn`: 44dp round, page-coloured fill (the outline comes from GlassRoundButton). */
private val glassRound = Modifier.size(44.dp).background(Glass.page, CircleShape)
/** The pill's word: "Auto", "Lens", "Local", "Mokuro". */
internal fun ReaderOcrSource.shortLabel() = when (this) {
    ReaderOcrSource.AUTOMATIC -> MR.strings.glass_ocr_short_auto
    ReaderOcrSource.MOKURO -> KMR.strings.ocr_source_mokuro
    ReaderOcrSource.GOOGLE_LENS -> MR.strings.glass_ocr_short_lens
    ReaderOcrSource.LOCAL -> KMR.strings.ocr_source_local
}

internal fun ReaderOcrSource.label() = when (this) {
    ReaderOcrSource.AUTOMATIC -> KMR.strings.ocr_source_automatic
    ReaderOcrSource.MOKURO -> KMR.strings.ocr_source_mokuro
    ReaderOcrSource.GOOGLE_LENS -> KMR.strings.ocr_source_google_lens
    ReaderOcrSource.LOCAL -> KMR.strings.ocr_source_local
}

private class Tool(val label: String, val onClick: () -> Unit, val glass: String? = null, val tint: Color = Color.White)

@Composable
private fun GlassReaderTools(
    modifier: Modifier,
    narrow: Boolean,
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    onClickMangaStats: (() -> Unit)?,
    currentReadingMode: ReadingMode,
    dualPageSplitEnabled: Boolean,
    doublePages: Boolean,
    onClickChapterList: () -> Unit,
    onClickWebView: (() -> Unit)?,
    onClickBrowser: (() -> Unit)?,
    onClickShare: (() -> Unit)?,
    onClickPageLayout: () -> Unit,
    onClickShiftPage: () -> Unit,
    onShowExhTools: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    // Same conditions as ReaderBottomBar for the two that only apply to some modes.
    val tools = buildList {
        add(Tool(stringResource(MR.strings.chapters), onClickChapterList, "list"))
        add(Tool(stringResource(MR.strings.pref_crop_borders), onClickCropBorder, "crop", if (cropEnabled) Glass.ice else Color.White))
        if (!dualPageSplitEnabled && ReadingMode.isPagerType(currentReadingMode.flagValue)) {
            add(Tool(stringResource(SYMR.strings.page_layout), onClickPageLayout, "book"))
        }
        if (doublePages) add(Tool(stringResource(SYMR.strings.shift_double_pages), onClickShiftPage, "shift"))
        onClickMangaStats?.let { add(Tool(stringResource(MR.strings.action_manga_stats), it, "stats")) }
        add(Tool(stringResource(MR.strings.action_settings), onClickSettings, "gear"))
        add(Tool(stringResource(MR.strings.glass_reader_more), { more = true }))
    }
    Row(modifier, horizontalArrangement = if (narrow) Arrangement.SpaceBetween else Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        tools.forEach { tool ->
            Box {
                if (tool.glass != null) {
                    GlassRoundButton(tool.glass, tool.onClick, glassRound, description = tool.label, outlined = true, tint = tool.tint, iconSize = 20.dp)
                } else {
                    Box(
                        glassRound.clip(CircleShape).border(1.dp, Glass.line, CircleShape).clickable(onClick = tool.onClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = tool.label, modifier = Modifier.size(22.dp), tint = Color.White)
                    }
                    GlassReaderTheme {
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            MoreItem(stringResource(MR.strings.viewer), onClickReadingMode, { more = false }) {
                                Icon(painterResource(readingMode.iconRes), contentDescription = null)
                            }
                            MoreItem(stringResource(MR.strings.pref_rotation_type), onClickOrientation, { more = false }) {
                                Icon(orientation.icon, contentDescription = null)
                            }
                            onClickWebView?.let { MoreItem(stringResource(MR.strings.action_open_in_web_view), it, { more = false }, Icons.Outlined.Public) }
                            onClickBrowser?.let { MoreItem(stringResource(MR.strings.action_open_in_browser), it, { more = false }, Icons.Outlined.Explore) }
                            onClickShare?.let { MoreItem(stringResource(MR.strings.action_share), it, { more = false }, Icons.Outlined.Share) }
                            MoreItem(stringResource(SYMR.strings.eh_autoscroll), onShowExhTools, { more = false }, Icons.Outlined.SlowMotionVideo)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreItem(text: String, onClick: () -> Unit, close: () -> Unit, icon: ImageVector) =
    MoreItem(text, onClick, close) { Icon(icon, contentDescription = null) }

@Composable
private fun MoreItem(text: String, onClick: () -> Unit, close: () -> Unit, icon: @Composable () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = {
            close()
            onClick()
        },
        leadingIcon = icon,
    )
}
