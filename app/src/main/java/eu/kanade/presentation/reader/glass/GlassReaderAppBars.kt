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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.reader.appbars.ExhUtils
import eu.kanade.presentation.reader.appbars.NavBarType
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.tachiyomi.BuildConfig
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
private val barColor = Glass.panel.copy(alpha = 0.94f)

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
    mokuroAvailable: Boolean,
    onToggleOcr: (() -> Unit)?,
    onSelectOcrSource: (ReaderOcrSource) -> Unit,
    ocrProgress: String?,
    pagePanel: (@Composable () -> Unit)?,
) {
    val isRtl = viewer is R2LPagerViewer
    val navigator: @Composable (Boolean) -> Unit = { vertical ->
        GlassReaderTheme {
            ChapterNavigator(
                isRtl = isRtl,
                isVerticalSlider = vertical,
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
    }

    Column(Modifier.fillMaxHeight()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(slide) { -it } + fadeIn(fade),
            exit = slideOutVertically(slide) { -it } + fadeOut(fade),
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
                    mokuroAvailable = mokuroAvailable,
                    onToggleOcr = onToggleOcr,
                    onSelectOcrSource = onSelectOcrSource,
                    ocrProgress = ocrProgress,
                )
                GlassReaderTheme {
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
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (navBarType == NavBarType.Bottom) navigator(false)
                GlassReaderTools(
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
                )
            }
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
    mokuroAvailable: Boolean,
    onToggleOcr: (() -> Unit)?,
    onSelectOcrSource: (ReaderOcrSource) -> Unit,
    ocrProgress: String?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(barColor)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(72.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoundButton("chevLeft", stringResource(MR.strings.action_bar_up_description), navigateUp)
        Column(Modifier.weight(1f).clickable(onClick = onClickTitle)) {
            Text(
                mangaTitle.orEmpty(),
                color = Color.White,
                fontSize = Glass.label,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            chapterTitle?.let {
                Text(it, color = Glass.secondary, fontSize = Glass.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ocrProgress?.let {
            Row(
                Modifier
                    .height(32.dp)
                    .border(1.dp, Glass.line, CircleShape)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(Modifier.size(12.dp), color = Glass.ice, strokeWidth = 2.dp)
                Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = Glass.caption)
            }
        }
        onToggleOcr?.let { toggle ->
            var menu by remember { mutableStateOf(false) }
            val enabled = ocrEnabled || !ocrLoading
            Box {
                Box(
                    Modifier
                        .height(44.dp)
                        .then(
                            if (ocrEnabled) {
                                Modifier.clip(CircleShape).background(Color.White)
                            } else {
                                Modifier.clip(CircleShape).border(1.dp, Glass.line, CircleShape)
                            },
                        )
                        .combinedClickable(enabled = enabled, onClick = toggle, onLongClick = { menu = true })
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(MR.strings.action_ocr),
                        color = if (ocrEnabled) Color.Black else Color.White,
                        fontSize = Glass.body,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                GlassReaderTheme {
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        ReaderOcrSource.availableSources(BuildConfig.HAS_LOCAL_OCR, mokuroAvailable).forEach { source ->
                            DropdownMenuItem(
                                text = { Text(stringResource(source.label())) },
                                onClick = {
                                    menu = false
                                    onSelectOcrSource(source)
                                },
                                trailingIcon = {
                                    if (source == ocrSource) Icon(Icons.Outlined.Check, contentDescription = null)
                                },
                            )
                        }
                    }
                }
            }
        }
        RoundButton(
            "bookmark",
            stringResource(if (bookmarked) MR.strings.action_remove_bookmark else MR.strings.action_bookmark),
            onToggleBookmarked,
            tint = if (bookmarked) Glass.ice else Color.White,
        )
    }
}

private fun ReaderOcrSource.label() = when (this) {
    ReaderOcrSource.AUTOMATIC -> KMR.strings.ocr_source_automatic
    ReaderOcrSource.MOKURO -> KMR.strings.ocr_source_mokuro
    ReaderOcrSource.GOOGLE_LENS -> KMR.strings.ocr_source_google_lens
    ReaderOcrSource.LOCAL -> KMR.strings.ocr_source_local
}

@Composable
private fun RoundButton(icon: String, description: String, onClick: () -> Unit, tint: Color = Color.White) {
    Box(
        Modifier.size(Glass.button).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlassIcon(icon, size = 22.dp, tint = tint, contentDescription = description)
    }
}

private class Tool(val label: String, val onClick: () -> Unit, val glass: String? = null, val tint: Color = Color.White)

@Composable
private fun GlassReaderTools(
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
    Row(
        Modifier
            .fillMaxWidth()
            .background(barColor)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(84.dp)
            .pointerInput(Unit) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tools.forEach { tool ->
            Box(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(Glass.r12))
                        .clickable(onClick = tool.onClick),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    if (tool.glass != null) {
                        GlassIcon(tool.glass, size = 22.dp, tint = tool.tint)
                    } else {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = null, modifier = Modifier.size(22.dp), tint = Color.White)
                    }
                    Text(tool.label, color = Color.White.copy(alpha = 0.85f), fontSize = Glass.caption, maxLines = 1)
                }
                if (tool.glass == null) {
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
