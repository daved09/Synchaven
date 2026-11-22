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

/** Vorschlagsfarben für die Grundfarbe (wie in der Weboberfläche) */
val SEED_COLORS = listOf(
    "#6750A4", "#0B57D0", "#006A6A", "#386A20", "#7D5260", "#B3261E",
    "#8B5000", "#5B5F97", "#00639B", "#984061", "#6D5E0F", "#4A6267",
)

/** Vorschlagsfarben für Kalender */
val CALENDAR_COLORS = listOf(
    "#6750A4", "#0B57D0", "#039BE5", "#00897B", "#33B679", "#7CB342",
    "#F6BF26", "#F4511E", "#D50000", "#E67C73", "#8E24AA", "#616161",
)

fun supportsDynamicColor() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** „#RRGGBB“ → Color; ungültige Werte ergeben das Standardviolett */
fun parseHexColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF6750A4))

/** Gut lesbare Textfarbe auf einer beliebigen Hintergrundfarbe */
fun onColor(background: Color): Color = if (background.luminance() > 0.4f) Color(0xFF1D1B20) else Color.White

/**
 * Material-3-Theme: Farben aus dem Hintergrundbild (Android 12+) oder ein „Tonal Spot“-Schema aus der
 * gewählten Grundfarbe – dasselbe Verfahren wie die Weboberfläche.
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
