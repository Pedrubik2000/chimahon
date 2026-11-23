package eu.kanade.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import eu.kanade.tachiyomi.util.system.isTabletUi

@Composable
@ReadOnlyComposable
fun isTabletUi(): Boolean {
    return LocalConfiguration.current.isTabletUi()
}

/** The device word the glass UI's strings use: the phone layout is the one shown when this is not a tablet. */
@Composable
@ReadOnlyComposable
fun glassDevice(): String = if (isTabletUi()) "tablet" else "phone"
