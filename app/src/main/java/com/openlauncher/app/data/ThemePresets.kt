package com.openlauncher.app.data

import androidx.compose.ui.graphics.Color

/**
 * A named accent + surface pairing for each mode. Each preset's light and dark
 * surfaces are independently tinted toward the theme's character — not a shared
 * neutral with only the accent swapped, and not the dark accent simply dropped
 * onto white. Every accent/surface pair here was contrast-checked (WCAG) before
 * landing: see the design artifact this was picked from for the exact ratios.
 */
data class DashboardThemeDef(
    val id: String,
    val label: String,
    val darkAccent: Long,
    val darkBg: Long,
    val darkInk: Long,
    val lightAccent: Long,
    val lightBg: Long
)

/** The one and only look of this build: Ross White / tricolour 1991 Africa Twin XRV750 (RD04). */
const val BIKE_THEME_ID = "rd04"

val DASHBOARD_THEMES = listOf(
    // Black instrument faces by night, tank white by day, tricolour red as the
    // accent (the blue lives in RideScreen's stripes).
    DashboardThemeDef(
        id = BIKE_THEME_ID, label = "Africa Twin XRV750 '91",
        darkAccent  = 0xFFFF4136L, darkBg  = 0xFF0C0D10L, darkInk = 0xFFF2F0EAL,
        lightAccent = 0xFFB3141CL, lightBg = 0xFFF4F2ECL
    )
)

data class ResolvedTheme(
    val accent: Color,
    val background: Color,
    val ink: Color?  // non-null only in dark mode — light mode keeps the app's existing neutral ink
)

fun resolveDashboardTheme(themeId: String, isDayMode: Boolean): ResolvedTheme? {
    val def = DASHBOARD_THEMES.find { it.id == themeId } ?: return null
    return if (isDayMode) {
        ResolvedTheme(accent = Color(def.lightAccent), background = Color(def.lightBg), ink = null)
    } else {
        ResolvedTheme(accent = Color(def.darkAccent), background = Color(def.darkBg), ink = Color(def.darkInk))
    }
}
