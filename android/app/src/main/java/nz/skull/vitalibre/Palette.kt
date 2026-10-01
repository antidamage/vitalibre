package nz.skull.vitalibre

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Linear mix toward `other`, `amount` 0..1. */
fun Color.mixed(other: Color, amount: Float): Color = lerp(this, other, amount.coerceIn(0f, 1f))

/**
 * Psionyk 1977, copied from the dashboard theme as APPLIED colours (raw palette rgb x intensity / 100).
 * Nothing here reads from, or refers to, the dashboard at runtime. Same values as the iOS build.
 */
class Palette(
    val isLight: Boolean,
    val background: Color,
    val panel: Color,
    val border: Color,
    val borderOpacity: Float,
    val accent: Color,
    val highlight: Color,
    val clock: Color,
    /** Every highlight outside the graph. Dark: the graph's peak colour. Light: the graph's third stop, since the pale peak cannot be read as text. */
    val led: Color,
    val plate: Color,
    val alert: Color,
    val spark: Color,
    /** The orb's own linework for the mode, painted. */
    val ringStops: List<Color>,
    /** The same colours without the line opacities. */
    val ringInk: List<Color>,
    /** Dome face treatment: the dark theme's orb uses the dark face, light the light face. */
    val lightFace: Boolean,
    val readout: Color,
    val readoutSecondary: Color,
) {
    val backgroundGradient: Brush
        get() = Brush.verticalGradient(
            listOf(
                background.mixed(Color.White, if (isLight) 0.35f else 0.035f),
                background,
                background.mixed(Color.Black, if (isLight) 0.05f else 0.25f),
            ),
        )

    /** Colour of ring hairlines and glow: the first ring colour lifted toward white. */
    val gridInk: Color get() = ringInk[0].mixed(Color.White, 0.55f)

    companion object {
        private val darkInk = listOf(Color(0xFFF00506), Color(0xFFFF002C), Color(0xFFFF2A66))
        private val lightInk = listOf(Color(0xFF00383B), Color(0xFF004D23), Color(0xFF002F54))

        val dark = Palette(
            isLight = false, background = Color(0xFF121212), panel = Color(0xFF171717),
            border = Color(0xFF1F1F1F), borderOpacity = 0.15f, accent = Color(0xFF42322A),
            highlight = Color(0xFF802E00), clock = Color(0xFF919191), led = Color(0xFFFF5A4F),
            plate = Color(0xFF1C1C1C), alert = Color(0xFFFF2F00), spark = Color(0xFFFF5A4F),
            ringStops = listOf(darkInk[0].copy(alpha = 0.80f), darkInk[1].copy(alpha = 0.90f), darkInk[2].copy(alpha = 0.80f)),
            ringInk = darkInk, lightFace = false,
            readout = Color(0xFFE4E4E4), readoutSecondary = Color(0xFF919191),
        )

        val light = Palette(
            isLight = true, background = Color(0xFFE8E8E8), panel = Color(0xFFFFFFFF),
            border = Color(0xFFFFFFFF), borderOpacity = 0.75f, accent = Color(0xFFFFFDFD),
            highlight = Color(0xFFC4C4C4), clock = Color(0xFFA1A1A1), led = Color(0xFF129C8E),
            plate = Color(0xFFFFFFFF), alert = Color(0xFF93FFF9), spark = Color(0xFF129C8E),
            ringStops = lightInk, ringInk = lightInk, lightFace = true,
            readout = Color(0xFF4A4A4A), readoutSecondary = Color(0xFF8A8A8A),
        )
    }
}

val LocalPalette = staticCompositionLocalOf { Palette.dark }

enum class ThemeMode(val label: String) { AUTO("Auto"), LIGHT("Light"), DARK("Dark") }
