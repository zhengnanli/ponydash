package dev.ponydash.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.ponydash.R

/** One colour theme. [matrix] is the single hue the Matrix view is built from. */
class Palette(
    val name: String,
    val bg: Color, val tile: Color, val stroke: Color, val label: Color, val dim: Color,
    val value: Color, val accent: Color, val good: Color, val warn: Color, val bad: Color,
    val track: Color, val matrix: Color,
    /** Matrix view drawn as dark ink on a light ground (for sun). */
    val matrixLight: Boolean = false,
)

object Themes {
    val all = listOf(
        Palette(
            "Midnight",
            bg = Color(0xFF0B1520), tile = Color(0xFF172435), stroke = Color(0xFF2A3F5A), label = Color(0xFF8FB4DA),
            dim = Color(0xFF5D7A9A), value = Color(0xFFFFFFFF), accent = Color(0xFF4DA3FF), good = Color(0xFF39D98A),
            warn = Color(0xFFF5A623), bad = Color(0xFFFF5A5F), track = Color(0xFF24344A), matrix = Color(0xFF4DA3FF),
        ),
        Palette(
            "Phosphor",
            bg = Color(0xFF020805), tile = Color(0xFF071A0F), stroke = Color(0xFF0F3D23), label = Color(0xFF3FCF7F),
            dim = Color(0xFF1F7A47), value = Color(0xFFC8FFD9), accent = Color(0xFF00FF66), good = Color(0xFF7CFFB2),
            warn = Color(0xFFFFD23F), bad = Color(0xFFFF4D4D), track = Color(0xFF0B2616), matrix = Color(0xFF00FF66),
        ),
        Palette(
            "Carbon",
            bg = Color(0xFF0E0E10), tile = Color(0xFF1B1B1F), stroke = Color(0xFF34343B), label = Color(0xFFB4B4BE),
            dim = Color(0xFF75757F), value = Color(0xFFFFFFFF), accent = Color(0xFFFF8A1F), good = Color(0xFF3DD68C),
            warn = Color(0xFFFFC233), bad = Color(0xFFFF4F4F), track = Color(0xFF2A2A30), matrix = Color(0xFF45E6FF),
        ),
        Palette(
            "Race Red",
            bg = Color(0xFF0D0809), tile = Color(0xFF1E1315), stroke = Color(0xFF42262B), label = Color(0xFFDDB1B6),
            dim = Color(0xFF936A70), value = Color(0xFFFFFFFF), accent = Color(0xFFFF3D3D), good = Color(0xFF4CD68F),
            warn = Color(0xFFFFB02E), bad = Color(0xFFFF2E5B), track = Color(0xFF301C1F), matrix = Color(0xFFFF4A4A),
        ),
        // Low blue light for night driving: everything warm, nothing white.
        Palette(
            "Night Amber",
            bg = Color(0xFF070500), tile = Color(0xFF151005), stroke = Color(0xFF3A2B0D), label = Color(0xFFCC9A3D),
            dim = Color(0xFF80612A), value = Color(0xFFFFC46A), accent = Color(0xFFFFAA1F), good = Color(0xFFB9C94A),
            warn = Color(0xFFFF8A1F), bad = Color(0xFFFF5233), track = Color(0xFF281E09), matrix = Color(0xFFFFAA1F),
        ),
        // For bright sun on the screen.
        Palette(
            "Daylight",
            bg = Color(0xFFEDF1F5), tile = Color(0xFFFFFFFF), stroke = Color(0xFFC5D0DC), label = Color(0xFF3E5066),
            dim = Color(0xFF718196), value = Color(0xFF0A1420), accent = Color(0xFF1765CC), good = Color(0xFF0E8A50),
            warn = Color(0xFFB86E00), bad = Color(0xFFD12C33), track = Color(0xFFD8E0E9), matrix = Color(0xFF1765CC),
            matrixLight = true,
        ),
    )

    fun byName(n: String?) = all.firstOrNull { it.name == n } ?: all[0]
}

object C {
    var palette by mutableStateOf(Themes.all[0])
    /** True while the theme follows the system light/dark setting. */
    var auto by mutableStateOf(true)

    val Bg get() = palette.bg
    val Tile get() = palette.tile
    val Stroke get() = palette.stroke
    val Label get() = palette.label
    val Dim get() = palette.dim
    val Value get() = palette.value
    val Accent get() = palette.accent
    val Good get() = palette.good
    val Warn get() = palette.warn
    val Bad get() = palette.bad
    val Track get() = palette.track
    val Matrix get() = palette.matrix
}

/** Bundled fonts: they render cleanly on low-density screens, unlike the system fallbacks. */
object Fonts {
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    private fun f(res: Int, w: Int) = Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

    val Sans = FontFamily(f(R.font.inter, 400), f(R.font.inter, 500), f(R.font.inter, 600), f(R.font.inter, 700), f(R.font.inter, 800))
    val Mono = FontFamily(f(R.font.jetbrains_mono, 400), f(R.font.jetbrains_mono, 500), f(R.font.jetbrains_mono, 700), f(R.font.jetbrains_mono, 800))

    val typography: Typography = Typography().run {
        Typography(
            displayLarge = displayLarge.copy(fontFamily = Sans), displayMedium = displayMedium.copy(fontFamily = Sans),
            displaySmall = displaySmall.copy(fontFamily = Sans), headlineLarge = headlineLarge.copy(fontFamily = Sans),
            headlineMedium = headlineMedium.copy(fontFamily = Sans), headlineSmall = headlineSmall.copy(fontFamily = Sans),
            titleLarge = titleLarge.copy(fontFamily = Sans), titleMedium = titleMedium.copy(fontFamily = Sans),
            titleSmall = titleSmall.copy(fontFamily = Sans), bodyLarge = bodyLarge.copy(fontFamily = Sans),
            bodyMedium = bodyMedium.copy(fontFamily = Sans), bodySmall = bodySmall.copy(fontFamily = Sans),
            labelLarge = labelLarge.copy(fontFamily = Sans), labelMedium = labelMedium.copy(fontFamily = Sans),
            labelSmall = labelSmall.copy(fontFamily = Sans),
        )
    }
}
