package com.erelay.relay.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.R
import com.erelay.relay.data.Mode

/**
 * The surfaces a theme changes. Everything else - text, the state colours,
 * jade - stays the same in all of them, so a status reads the same however
 * dark the page behind it is.
 */
enum class Palette(
    val key: String,
    val label: String,
    val bg: Color,
    val surface: Color,
    val control: Color,
    val controlHover: Color,
    val controlActive: Color,
    /** The faint violet light at the top of every page. */
    val glow: Color,
    /** A light page: dark text, dark hairlines, deeper status colours. */
    val light: Boolean = false,
) {
    /** app.css's dark theme: near-black with violet in it. */
    MIDNIGHT("midnight", "Midnight", Color(0xFF09090E), Color(0xFF17171F), Color(0xFF20212B),
        Color(0xFF292B37), Color(0xFF313440), Color(0x2E483AC2)),
    /** app.css's other dark set: neutral grey, no colour cast. */
    GRAPHITE("graphite", "Graphite", Color(0xFF111113), Color(0xFF18191B), Color(0xFF212225),
        Color(0xFF272A2D), Color(0xFF2E3135), Color(0x14FFFFFF)),
    /** True black: the pixels of an OLED screen switched off. */
    BLACK("black", "Black", Color(0xFF000000), Color(0xFF0E0E12), Color(0xFF18181F),
        Color(0xFF202029), Color(0xFF2A2A34), Color(0x1A483AC2)),
    /** Deep navy, a cold blue light at the top. */
    OCEAN("ocean", "Ocean", Color(0xFF060B16), Color(0xFF0F1828), Color(0xFF172236),
        Color(0xFF1E2B42), Color(0xFF26344E), Color(0x332A6FDB)),
    /** Near-black green. */
    FOREST("forest", "Forest", Color(0xFF060E0B), Color(0xFF0F1A16), Color(0xFF16241F),
        Color(0xFF1D2D27), Color(0xFF25372F), Color(0x2E1F9D6B)),
    /** Aubergine. */
    PLUM("plum", "Plum", Color(0xFF0E0812), Color(0xFF1B1222), Color(0xFF261A2F),
        Color(0xFF30213B), Color(0xFF3A2946), Color(0x38A23AC2)),
    /** Warm, embers in a dark room. */
    EMBER("ember", "Ember", Color(0xFF110A08), Color(0xFF1E1512), Color(0xFF2A1D18),
        Color(0xFF34241E), Color(0xFF3F2C25), Color(0x33D9622B)),
    /** Blue-grey, like slate in the rain. */
    SLATE("slate", "Slate", Color(0xFF0C0F13), Color(0xFF161B21), Color(0xFF1F262E),
        Color(0xFF27303A), Color(0xFF303A45), Color(0x24607D9B)),
    /** Paper: an off-white page with white cards, for daylight. */
    WHITE("white", "White", Color(0xFFF4F5F8), Color(0xFFFFFFFF), Color(0xFFECEEF2),
        Color(0xFFE4E7EC), Color(0xFFDCE0E7), Color(0x266C5CE7), light = true);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: MIDNIGHT
    }
}

/**
 * The desktop's palette, `app/ui/app.css` in its dark theme, name for name.
 * A colour changed there should be findable here by searching for the word.
 *
 * The surfaces read [palette], which is Compose state: switching the theme
 * repaints every screen that drew with them, with nothing passed down.
 */
object Tone {
    var palette by mutableStateOf(Palette.MIDNIGHT)

    /** Traffic-light orb colours rather than the desktop's. */
    var signal by mutableStateOf(true)

    val bg get() = palette.bg
    val surface get() = palette.surface
    val control get() = palette.control
    val controlHover get() = palette.controlHover
    val controlActive get() = palette.controlActive
    val glow get() = palette.glow
    val light get() = palette.light

    // Text, hairlines and the state colours turn over with the page. The
    // dark set is app.css's; the light set keeps the same hues a step deeper,
    // because mint and amber that glow on near-black wash out on white.
    val divider get() = if (light) Color(0x17000000) else Color(0x17FFFFFF)
    val border get() = if (light) Color(0x24000000) else Color(0x21FFFFFF)

    val text get() = if (light) Color(0xFF16171D) else Color(0xFFF5F5F8)
    val textMuted get() = if (light) Color(0xFF474B57) else Color(0xFFB9BAC6)
    val textOff get() = if (light) Color(0xFF7A7F8E) else Color(0xFF777987)

    val ok get() = if (light) Color(0xFF0C9C72) else Color(0xFF61EFBF)
    val work get() = if (light) Color(0xFFB7791F) else Color(0xFFFFC761)
    val fail get() = if (light) Color(0xFFD23B54) else Color(0xFFFF8F9B)
    val failBg = Color(0x80651D2C)
    val focus get() = if (light) Color(0xFF0C9C72) else Color(0xFF74E4C2)
    val accent get() = if (light) Color(0xFF5B4FE0) else Color(0xFF8C82FF)

    /** What a press lights a control with: white on a dark page, black on a light one. */
    val wash get() = if (light) Color.Black else Color.White

    val jade2 = Color(0xFF4AE5C3)
    val jadeDeep = Color(0xFF0B6B52)
    val ink = Color(0xFF06281D)

    val fluxDown get() = ok
    val fluxUp get() = if (light) Color(0xFF6A5AE0) else Color(0xFF9C8CFF)

    val surfshark = Color(0xFF3BC1C9)
    val windscribe get() = ok
}

/** The orb's colours per state: `--blob-a/b/c`, `--blob-glow`, `--state-accent`. */
data class StateTone(val a: Color, val b: Color, val c: Color, val glow: Color, val accent: Color)

private fun glow(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a * 255).toInt())

/**
 * The look per state. Two sets: the traffic light (red off, yellow working,
 * green on - read at a glance, across a room) and the desktop's own app.css
 * colours, lavender at rest. Which one is Look and feel's choice, read from
 * [Tone.signal] so a change repaints at once.
 */
fun toneOf(mode: Mode): StateTone = if (Tone.signal) when (mode) {
    Mode.OFF -> StateTone(Color(0xFFFF8A8A), Color(0xFFFF5A6E), Color(0xFF8E1D2E),
        glow(255, 80, 96, 0.40f), Color(0xFFFF6B7A))
    Mode.BUSY -> StateTone(Color(0xFFFFE27A), Color(0xFFFFC24B), Color(0xFFB9791A),
        glow(255, 200, 70, 0.42f), Color(0xFFFFD261))
    Mode.ON -> StateTone(Color(0xFF71F1BD), Color(0xFF3DD4EE), Color(0xFF087E6A),
        glow(48, 218, 171, 0.42f), Color(0xFF61EFBF))
    Mode.FAIL -> StateTone(Color(0xFFFF4D7A), Color(0xFFC2185B), Color(0xFF4E0A26),
        glow(220, 40, 90, 0.42f), Color(0xFFFF4D7A))
} else when (mode) {
    Mode.OFF -> StateTone(Color(0xFF9C8CFF), Color(0xFF4CE2D2), Color(0xFF483AC2),
        glow(102, 90, 231, 0.48f), Color(0xFF8C82FF))
    Mode.BUSY -> StateTone(Color(0xFFFFCB67), Color(0xFFFF778E), Color(0xFF9B4BDA),
        glow(255, 156, 83, 0.42f), Color(0xFFFFC761))
    Mode.ON -> StateTone(Color(0xFF71F1BD), Color(0xFF3DD4EE), Color(0xFF087E6A),
        glow(48, 218, 171, 0.42f), Color(0xFF61EFBF))
    Mode.FAIL -> StateTone(Color(0xFFFF8495), Color(0xFFB968E7), Color(0xFF8B2849),
        glow(223, 70, 106, 0.36f), Color(0xFFFF8F9B))
}

/**
 * The orb's colours when it is not showing a connection at all - the intro,
 * the welcome, the setup. The desktop's own resting lavender and teal: red
 * would say "not connected" to somebody who has not been asked to connect.
 */
fun restTone(): StateTone = StateTone(Color(0xFF9C8CFF), Color(0xFF4CE2D2), Color(0xFF483AC2),
    glow(102, 90, 231, 0.48f), Color(0xFF8C82FF))

/** Radii and timings, also the desktop's. */
object Shape {
    val card = 20.dp
    val control = 12.dp
    const val ENTER_MS = 167
    const val FADE_MS = 83
    const val SINK_MS = 130
    /** cubic-bezier(0, 0, 0, 1): leaves at once, arrives slowly. */
    val ease = CubicBezierEasing(0f, 0f, 0f, 1f)
    /** --swing: cubic-bezier(0.22, 1, 0.36, 1). */
    val swing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
}

/**
 * IBM Plex Sans, the desktop's face, as one static file per weight cut from
 * its variable font. Not the variable font itself: MIUI and some other skins
 * ignore the weight axis and draw every weight at the lightest, which turned
 * every heading on a Poco F3 into body text.
 */
val Sans = FontFamily(
    Font(R.font.plex_sans_400, FontWeight.W400),
    Font(R.font.plex_sans_500, FontWeight.W500),
    Font(R.font.plex_sans_600, FontWeight.W600),
    Font(R.font.plex_sans_700, FontWeight.W700),
)

/** IBM Plex Mono: anything that is a value rather than a sentence. */
val Mono = FontFamily(
    Font(R.font.plex_mono, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

object Type {
    val eyebrow get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 10.sp,
        letterSpacing = 1.4.sp, color = Tone.textOff)
    val kicker get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W700, fontSize = 10.sp,
        letterSpacing = 1.6.sp, color = Tone.ok)
    val title get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W700, fontSize = 20.sp,
        color = Tone.text)
    val status get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W700, fontSize = 20.sp,
        letterSpacing = 0.4.sp, color = Tone.text)
    val body get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 14.sp,
        lineHeight = 20.sp, color = Tone.textMuted)
    val strong get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 15.sp,
        color = Tone.text)
    val small get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 12.5.sp,
        lineHeight = 18.sp, color = Tone.textOff)
    val mono get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp,
        color = Tone.textOff)
    val ip get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 16.sp,
        color = Tone.text)
    val button get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 15.sp,
        color = Tone.text)
}

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (Tone.light) lightColorScheme(
            background = Tone.bg,
            surface = Tone.surface,
            primary = Tone.ok,
            onPrimary = Tone.ink,
            secondary = Tone.accent,
            error = Tone.fail,
            onSurface = Tone.text,
            onBackground = Tone.text,
        ) else darkColorScheme(
            background = Tone.bg,
            surface = Tone.surface,
            primary = Tone.ok,
            onPrimary = Tone.ink,
            secondary = Tone.accent,
            error = Tone.fail,
            onSurface = Tone.text,
            onBackground = Tone.text,
        ),
        content = content,
    )
}
