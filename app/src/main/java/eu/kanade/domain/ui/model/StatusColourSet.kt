package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** The glass pages' volume status colours (title grid, legend, source boxes); the colours are in `StatusPalette`. */
enum class StatusColourSet(val titleRes: StringResource) {
    DEFAULT(MR.strings.glass_status_set_default),
    GREEN_RED(MR.strings.glass_status_set_green_red),
    ICE_AMBER(MR.strings.glass_status_set_ice_amber),
}
