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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.erelay.relay.R
import com.erelay.relay.data.Mode

/*
 * "Instrument": three themes, no brand accent. The only colour that means
 * anything is the connection state (off / work / ok / fail), and it is the
 * accent. The tokens are .claude/DESIGN.md's table, name for name.
 */

/** The three themes. Every older theme name maps to [GRAPHITE], except white / light. */
enum class Palette(
    val key: String,
    val label: String,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val stroke: Color,
    val strokeStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val off: Color,
    val work: Color,
    val ok: Color,
    val fail: Color,
    val light: Boolean = false,
) {
    /** The default: dark, neutral. */
    GRAPHITE("graphite", "Graphite",
        bg = Color(0xFF121316), surface = Color(0xFF1A1B1F), surface2 = Color(0xFF24252A),
        stroke = Color(0x14FFFFFF), strokeStrong = Color(0x29FFFFFF),
        text = Color(0xFFECEDEF), text2 = Color(0xFFA3A6AE), text3 = Color(0xFF6E717A),
        off = Color(0xFF8A8D96), work = Color(0xFFE8B24A), ok = Color(0xFF3FCF8E), fail = Color(0xFFF0646E)),

    /** A light page. */
    WHITE("white", "White",
        bg = Color(0xFFF6F6F7), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFECECEF),
        stroke = Color(0x17000000), strokeStrong = Color(0x29000000),
        text = Color(0xFF16171A), text2 = Color(0xFF555862), text3 = Color(0xFF8A8D96),
        off = Color(0xFF6E717A), work = Color(0xFFB7791F), ok = Color(0xFF1F9D63), fail = Color(0xFFD1434F),
        light = true),

    /** True black: the pixels of an OLED screen switched off. */
    BLACK("black", "Black",
        bg = Color(0xFF000000), surface = Color(0xFF0D0D0F), surface2 = Color(0xFF18181B),
        stroke = Color(0x17FFFFFF), strokeStrong = Color(0x2EFFFFFF),
        text = Color(0xFFF2F2F3), text2 = Color(0xFFA3A6AE), text3 = Color(0xFF6E717A),
        off = Color(0xFF8A8D96), work = Color(0xFFE8B24A), ok = Color(0xFF3FCF8E), fail = Color(0xFFF0646E));

    companion object {
        fun of(key: String?) = when (key?.lowercase()) {
            "white", "light" -> WHITE
            "black" -> BLACK
            else -> GRAPHITE
        }
    }
}

/**
 * The palette in use. It is Compose state: switching the theme repaints every
 * screen that drew with it, with nothing passed down.
 *
 * [mode] is the connection state, set once at the root ([com.erelay.relay.ui.Root]);
 * [state] is its colour, the one accent the app has.
 */
object Tone {
    var palette by mutableStateOf(Palette.GRAPHITE)

    /** What the tunnel is doing, for everything that colours itself by it. */
    var mode by mutableStateOf(Mode.OFF)

    val light get() = palette.light

    val bg get() = palette.bg
    val surface get() = palette.surface
    val surface2 get() = palette.surface2
    val stroke get() = palette.stroke
    val strokeStrong get() = palette.strokeStrong

    val text get() = palette.text
    val text2 get() = palette.text2
    val text3 get() = palette.text3

    val off get() = palette.off
    val work get() = palette.work
    val ok get() = palette.ok
    val fail get() = palette.fail

    /** The state colour: the accent. */
    val state get() = stateOf(mode)
    fun stateOf(m: Mode): Color = when (m) {
        Mode.OFF -> palette.off
        Mode.BUSY -> palette.work
        Mode.ON -> palette.ok
        Mode.FAIL -> palette.fail
    }

    /** Text on a fill in the state colour. */
    val ink get() = if (light) Color.White else Color(0xFF0B0C0E)

    /** Focus rings stay text-coloured. */
    val focus get() = palette.text
    val failBg get() = palette.fail.copy(alpha = 0.16f)
    /** What a press lights a control with: white on a dark page, black on a light one. */
    val wash get() = if (light) Color.Black else Color.White
}

/** The line colour of the hologram globe for one state. */
data class StateTone(val line: Color)

/**
 * The globe's line colour per state: the Kootaj hologram's own cyan while
 * disconnected, then the state colour itself (amber working, green connected,
 * red failed) so the globe says the same thing the status word does. On a
 * light page the cyan is deepened so it reads as ink. Read from the current
 * palette, so call it where a palette change should recompose.
 */
fun toneOf(mode: Mode): StateTone = StateTone(
    when (mode) {
        Mode.OFF -> if (Tone.light) Color(0xFF0A8C94) else Color(0xFF19D3CB)
        else -> Tone.stateOf(mode)
    })

/** The globe when it is not showing a connection at all - the welcome, the setup. */
fun restTone(): StateTone = toneOf(Mode.OFF)

/** Radii, and the timings the motion spec names. */
object Shape {
    /** Top corners of a bottom sheet. */
    val sheet = 28.dp
    /** A card: rare. */
    val card = 16.dp
    /** Controls, chips, a row's pressed backplate. */
    val control = 12.dp
    const val ENTER_MS = 250
    const val FADE_MS = 83
    const val SINK_MS = 167
    /** The big moments: the backdrop, a screen change. */
    const val LARGE_MS = 333
    /** cubic-bezier(0, 0, 0, 1): leaves at once, arrives slowly. */
    val ease = CubicBezierEasing(0f, 0f, 0f, 1f)
    /** cubic-bezier(0.55, 0.55, 0, 1): point to point. */
    val swing = CubicBezierEasing(0.55f, 0.55f, 0f, 1f)
}

/** The 4-pt ramp. Nothing else is a gap. */
object Space {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp
    val x8 = 32.dp
    val x10 = 40.dp
    val x12 = 48.dp
    val x16 = 64.dp
}

/**
 * IBM Plex Sans as one static file per weight cut from its variable font.
 * Not the variable font itself: MIUI and some other skins ignore the weight
 * axis and draw every weight at the lightest. The app uses 400 and 600 only.
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

/**
 * The scale: 12 / 14 / 16 / 20 / 28 / 44 sp, weights 400 and 600, no
 * uppercase and no wide tracking. Anything that updates live is tabular.
 */
object Type {
    /** 12/16 - metadata, counts, helper text. */
    val caption get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 12.sp,
        lineHeight = 16.sp, color = Tone.text2)
    /** 14/20 - rows, labels, paragraphs. */
    val body get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 14.sp,
        lineHeight = 20.sp, color = Tone.text2)
    /** 14/20 600 - row titles. */
    val strong get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 14.sp,
        lineHeight = 20.sp, color = Tone.text)
    /** 16/22 600 - a heading inside a screen. */
    val subtitle get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 16.sp,
        lineHeight = 22.sp, color = Tone.text)
    /** 20/28 600 - screen titles, the location line on home. */
    val title get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 20.sp,
        lineHeight = 28.sp, color = Tone.text)
    /** 28/36 600 - a big value when one is needed. */
    val display get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 28.sp,
        lineHeight = 36.sp, color = Tone.text)
    /** 44/48 600, tight - the one big word on home. */
    val status get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 44.sp,
        lineHeight = 48.sp, letterSpacing = (-0.02).em, color = Tone.text)

    /** 12/16 mono, tabular - ping, hosts, ports. */
    val mono get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp,
        lineHeight = 16.sp, fontFeatureSettings = "tnum", color = Tone.text2)
    /** 14/20 mono, tabular - the timer. */
    val monoBody get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp,
        lineHeight = 20.sp, fontFeatureSettings = "tnum", color = Tone.text)
    /** An address that has to be read: 14/20 mono. */
    val ip get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp,
        lineHeight = 20.sp, fontFeatureSettings = "tnum", color = Tone.text)
    /** Buttons: 16/24 600. */
    val button get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 16.sp,
        lineHeight = 24.sp, color = Tone.text)
}

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    val p = Tone.palette
    MaterialTheme(
        colorScheme = if (p.light) lightColorScheme(
            background = p.bg, surface = p.surface, onSurface = p.text, onBackground = p.text,
            onSurfaceVariant = p.text2, outline = p.strokeStrong, outlineVariant = p.stroke,
            primary = Tone.state, onPrimary = Tone.ink, error = p.fail,
            surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
            surfaceContainerHighest = p.surface2, surfaceVariant = p.surface2,
            secondaryContainer = p.surface2, onSecondaryContainer = p.text,
        ) else darkColorScheme(
            background = p.bg, surface = p.surface, onSurface = p.text, onBackground = p.text,
            onSurfaceVariant = p.text2, outline = p.strokeStrong, outlineVariant = p.stroke,
            primary = Tone.state, onPrimary = Tone.ink, error = p.fail,
            surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
            surfaceContainerHighest = p.surface2, surfaceVariant = p.surface2,
            secondaryContainer = p.surface2, onSecondaryContainer = p.text,
        ),
        content = content,
    )
}
