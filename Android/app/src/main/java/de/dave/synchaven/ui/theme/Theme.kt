package de.dave.synchaven.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import de.dave.synchaven.data.store.AppSettings
import de.dave.synchaven.data.store.ThemeMode

/** Suggested colors for the base color (as in the web UI) */
val SEED_COLORS = listOf(
    "#6750A4", "#0B57D0", "#006A6A", "#386A20", "#7D5260", "#B3261E",
    "#8B5000", "#5B5F97", "#00639B", "#984061", "#6D5E0F", "#4A6267",
)

/** Suggested colors for calendars */
val CALENDAR_COLORS = listOf(
    "#6750A4", "#0B57D0", "#039BE5", "#00897B", "#33B679", "#7CB342",
    "#F6BF26", "#F4511E", "#D50000", "#E67C73", "#8E24AA", "#616161",
)

fun supportsDynamicColor() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** "#RRGGBB" → Color; invalid values result in the default violet */
fun parseHexColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF6750A4))

/** Readable text color on any background color */
fun onColor(background: Color): Color = if (background.luminance() > 0.4f) Color(0xFF1D1B20) else Color.White

/**
 * Material 3 theme: colors from the wallpaper (Android 12+) or a "Tonal Spot" scheme from the
 * selected base color – the same method as the web UI.
 */
@Composable
fun SyncHavenTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val seeded = rememberDynamicColorScheme(
        seedColor = parseHexColor(settings.seedColor),
        isDark = dark,
        style = PaletteStyle.TonalSpot,
    )
    val colors = if (settings.dynamicColor && supportsDynamicColor()) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else seeded
    MaterialTheme(colorScheme = colors, content = content)
}
