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
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.erelay.relay.R
import com.erelay.relay.data.Mode

/*
 * "Night room" (.claude/DESIGN-NIGHT.md): three themes. Neutral surfaces,
 * and one accent - Spotify Pulse - that belongs to the active item, the live
 * dot and the "Connected" state. The tokens are that file's §2, name for name.
 */

/** The three themes. Every older theme name maps to [GRAPHITE], except white / light. */
enum class Palette(
    val key: String,
    val label: String,
    /** Page. */
    val bg: Color,
    /** Slate Card. */
    val surface: Color,
    /** Raised Slate: pressed, chips, icon wells. */
    val surface2: Color,
    /** Edge: a card's 1dp border. */
    val stroke: Color,
    /** A ghost button's or a field's border. */
    val strokeStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val off: Color,
    val work: Color,
    val ok: Color,
    val fail: Color,
    /** Hairline: dividers. */
    val hairline: Color,
    /** Spotify Pulse: the active item, the live dot, the focus ring, "Connected". */
    val pulse: Color,
    /** Spotify Halo: the glow behind the active item and the live dot. */
    val halo: Color,
    /** Ink Glass: the fill of a floating layer, over whatever is behind it. */
    val glass: Color,
    /** The solid stand-in where nothing is behind to frost. */
    val glassSolid: Color,
    /** The brighter top edge of a floating layer. */
    val glassTop: Color,
    /** Status "Good": a success said with a check and a word, never a glow. Not Pulse. */
    val good: Color,
    val light: Boolean = false,
) {
    /** The default: Night, a neutral near-black. */
    GRAPHITE("graphite", "Graphite",
        bg = Color(0xFF0A0A0C), surface = Color(0xFF101013), surface2 = Color(0xFF17171B),
        stroke = Color(0x1AFFFFFF), strokeStrong = Color(0x2EFFFFFF),
        text = Color(0xFFF4F4F6), text2 = Color(0xFFA3A3AB), text3 = Color(0xFF8A8A94),
        off = Color(0xFF8A8A94), work = Color(0xFFFFC857), ok = Color(0xFF1ED760), fail = Color(0xFFFF6B7A),
        hairline = Color(0x12FFFFFF), pulse = Color(0xFF1ED760), halo = Color(0x731ED760),
        glass = Color(0x0BFFFFFF), glassSolid = Color(0xFF151518), glassTop = Color(0x2EFFFFFF),
        good = Color(0xFF6BE38B)),

    /** A light page. */
    WHITE("white", "White",
        bg = Color(0xFFF6F6F7), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFECECEF),
        stroke = Color(0x17000000), strokeStrong = Color(0x29000000),
        text = Color(0xFF16171A), text2 = Color(0xFF555862), text3 = Color(0xFF6B6E78),
        off = Color(0xFF6B6E78), work = Color(0xFFB7791F), ok = Color(0xFF139A48), fail = Color(0xFFD1434F),
        hairline = Color(0x0F000000), pulse = Color(0xFF139A48), halo = Color(0x00139A48),
        glass = Color(0x9EFFFFFF), glassSolid = Color(0xFFFFFFFF), glassTop = Color(0xFFFFFFFF),
        good = Color(0xFF2A8A4A), light = true),

    /** Void: the page is the outermost background, the cards sit on it. */
    BLACK("black", "Black",
        bg = Color(0xFF060607), surface = Color(0xFF101013), surface2 = Color(0xFF17171B),
        stroke = Color(0x1AFFFFFF), strokeStrong = Color(0x2EFFFFFF),
        text = Color(0xFFF4F4F6), text2 = Color(0xFFA3A3AB), text3 = Color(0xFF8A8A94),
        off = Color(0xFF8A8A94), work = Color(0xFFFFC857), ok = Color(0xFF1ED760), fail = Color(0xFFFF6B7A),
        hairline = Color(0x12FFFFFF), pulse = Color(0xFF1ED760), halo = Color(0x731ED760),
        glass = Color(0x0BFFFFFF), glassSolid = Color(0xFF151518), glassTop = Color(0x2EFFFFFF),
        good = Color(0xFF6BE38B));

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
    val hairline get() = palette.hairline
    val pulse get() = palette.pulse
    val halo get() = palette.halo
    val glass get() = palette.glass
    val glassSolid get() = palette.glassSolid
    val glassTop get() = palette.glassTop
    /** The neon is rationed, and off on a white page: the edge bar and the fill carry "active" there. */
    val glows get() = !palette.light

    val text get() = palette.text
    val text2 get() = palette.text2
    val text3 get() = palette.text3

    val off get() = palette.off
    val work get() = palette.work
    val ok get() = palette.ok
    val fail get() = palette.fail
    /** Success: the small check and its word. */
    val good get() = palette.good

    /** The state colour: the accent. */
    val state get() = stateOf(mode)
    fun stateOf(m: Mode): Color = when (m) {
        Mode.OFF -> palette.off
        Mode.BUSY -> palette.work
        Mode.ON -> palette.ok
        Mode.FAIL -> palette.fail
    }

    /** Text on a fill in the state colour, and on the primary button's Mist White. */
    val ink get() = if (light) Color.White else Color(0xFF060607)

    /** Focus rings are Pulse. */
    val focus get() = palette.pulse
    val failBg get() = palette.fail.copy(alpha = 0.16f)
    /** What a press lights a control with: white on a dark page, black on a light one. */
    val wash get() = if (light) Color.Black else Color.White
}

/**
 * The pastel data colours (DESIGN-NIGHT 2): charts and badges only, never
 * chrome. Deepened on the white page so a 1.5dp stroke still reads.
 */
object Data {
    /** Surfshark; the download line. */
    val periwinkle get() = if (Tone.light) Color(0xFF4F6FD8) else Color(0xFF8AA6FF)
    /** Windscribe. */
    val aqua get() = if (Tone.light) Color(0xFF138E9A) else Color(0xFF62E6F2)
    val peach get() = if (Tone.light) Color(0xFFC8642E) else Color(0xFFFFA877)
    /** The upload line. */
    val orchid get() = if (Tone.light) Color(0xFF8E52C9) else Color(0xFFCF9BFF)
    val lemon get() = if (Tone.light) Color(0xFF9A7B00) else Color(0xFFFFE27A)
    val rose get() = if (Tone.light) Color(0xFFC94A86) else Color(0xFFFF8FC3)

    /** A provider's own colour: Surfshark periwinkle, Windscribe aqua. */
    fun provider(key: String): Color = when (key) {
        "surfshark" -> periwinkle
        "windscribe" -> aqua
        else -> Tone.text3
    }
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

/** Radii (DESIGN-NIGHT 4), and the timings the motion spec names. */
object Shape {
    /** A bottom sheet's top corners, a panel. */
    val sheet = 28.dp
    /** A card. */
    val card = 28.dp
    /** A tooltip, a menu, a field. */
    val tip = 14.dp
    /** A small well, a pressed row's backplate. */
    val control = 12.dp
    /** Chips, pills, buttons. */
    val pill = 999.dp
    const val ENTER_MS = 250
    const val FADE_MS = 83
    const val SINK_MS = 167
    /** The big moments: the backdrop, a screen change. */
    const val LARGE_MS = 333
    /** cubic-bezier(0, 0, 0, 1): leaves at once, arrives slowly. */
    val ease = CubicBezierEasing(0f, 0f, 0f, 1f)
    /** cubic-bezier(0.55, 0.55, 0, 1): point to point. */
    val swing = CubicBezierEasing(0.55f, 0.55f, 0f, 1f)

    /* The motion spec's named eases (DESIGN-NIGHT 6), as their usual cubic-bezier stand-ins. */
    /** expo.out: a drawer opening. */
    val expoOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    /** power2.in: a drawer closing. */
    val power2In = CubicBezierEasing(0.55f, 0.085f, 0.68f, 0.53f)
    /** power2.out: a number settling. */
    val power2Out = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)
    /** power3.out: the active glow moving. */
    val power3Out = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
    /** Drawer open / close, ms. */
    const val DRAWER_OPEN_MS = 300
    const val DRAWER_CLOSE_MS = 180
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
 * Geist (OFL, github.com/vercel/geist-font) as one static file per weight:
 * the variable font is not used because MIUI and some other skins ignore the
 * weight axis and draw every weight at the lightest. Persian has no glyphs in
 * it: text in Persian is set in [Persian] instead, through [script].
 */
val Sans = FontFamily(
    Font(R.font.geist_400, FontWeight.W400),
    Font(R.font.geist_500, FontWeight.W500),
    Font(R.font.geist_600, FontWeight.W600),
)

/**
 * Estedad (OFL, github.com/aminabedi68/Estedad; licence in assets/OFL-Estedad.txt):
 * Persian, 400 body / 500 titles / 600 hero. One variable file, its weight set
 * per entry (Android 8+; older phones draw it at 400).
 */
@OptIn(ExperimentalTextApi::class)
val Persian = FontFamily(
    Font(R.font.estedad, FontWeight.W400, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.estedad, FontWeight.W500, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.estedad, FontWeight.W600, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

/** Whether [s] has any Arabic-script letter in it: Persian, for the type's purposes. */
fun isPersian(s: CharSequence): Boolean = s.any { it in '\u0600'..'\u06FF' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFC' }

/**
 * This style, or its Persian twin when [text] is Persian: Estedad, the labels
 * half a size up, line height 1.9. For text the app does not write itself -
 * an app's name, an account's label, a server's nickname.
 */
fun TextStyle.script(text: CharSequence): TextStyle {
    if (!isPersian(text)) return this
    val size = if (fontSize.isSp && fontSize.value < 13f) 13.sp else fontSize
    return copy(fontFamily = Persian, fontSize = size,
        lineHeight = if (size.isSp) (size.value * 1.9f).sp else lineHeight, letterSpacing = 0.sp)
}

/** Geist Mono: anything that is a value rather than a sentence. */
val Mono = FontFamily(
    Font(R.font.geist_mono, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

/**
 * The scale (DESIGN-NIGHT 3): hierarchy by weight and colour more than size -
 * labels 12.5 Dust, body 14 Fog, card titles 15/500 Mist White, display tight.
 * No uppercase and no wide tracking. Anything that updates live is tabular.
 */
object Type {
    /** 12.5/17 - metadata, counts, helper text: Dust. */
    val caption get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 12.5.sp,
        lineHeight = 17.sp, color = Tone.text3)
    /** 14/20 - paragraphs. */
    val body get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 14.sp,
        lineHeight = 20.sp, color = Tone.text2)
    /** 15/21 500 - row and card titles: Mist White. */
    val strong get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 15.sp,
        lineHeight = 21.sp, color = Tone.text)
    /** 16/22 500 - a heading inside a screen. */
    val subtitle get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 16.sp,
        lineHeight = 22.sp, letterSpacing = (-0.005).em, color = Tone.text)
    /** 20/28 500 - screen titles, the location line on home. */
    val title get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 20.sp,
        lineHeight = 28.sp, letterSpacing = (-0.01).em, color = Tone.text)
    /** 28/36 600 - a big value when one is needed. */
    val display get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 28.sp,
        lineHeight = 36.sp, letterSpacing = (-0.02).em, color = Tone.text)
    /** 44/48 600, tight - the one big word on home. */
    val status get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 44.sp,
        lineHeight = 48.sp, letterSpacing = (-0.03).em, color = Tone.text)

    /** 12/16 mono, tabular - ping, hosts, ports. */
    val mono get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp,
        lineHeight = 16.sp, fontFeatureSettings = "tnum", color = Tone.text2)
    /** 14/20 mono, tabular - the timer. */
    val monoBody get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp,
        lineHeight = 20.sp, fontFeatureSettings = "tnum", color = Tone.text)
    /** An address that has to be read: 14/20 mono. */
    val ip get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp,
        lineHeight = 20.sp, fontFeatureSettings = "tnum", color = Tone.text)
    /** Buttons: 15/22 500. */
    val button get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 15.sp,
        lineHeight = 22.sp, color = Tone.text)
}

/** Material's own type roles, set in Geist, so anything Material draws by itself matches. */
private fun androidx.compose.material3.Typography.inSans() = androidx.compose.material3.Typography(
    displayLarge = displayLarge.copy(fontFamily = Sans), displayMedium = displayMedium.copy(fontFamily = Sans),
    displaySmall = displaySmall.copy(fontFamily = Sans), headlineLarge = headlineLarge.copy(fontFamily = Sans),
    headlineMedium = headlineMedium.copy(fontFamily = Sans), headlineSmall = headlineSmall.copy(fontFamily = Sans),
    titleLarge = titleLarge.copy(fontFamily = Sans), titleMedium = titleMedium.copy(fontFamily = Sans),
    titleSmall = titleSmall.copy(fontFamily = Sans), bodyLarge = bodyLarge.copy(fontFamily = Sans),
    bodyMedium = bodyMedium.copy(fontFamily = Sans), bodySmall = bodySmall.copy(fontFamily = Sans),
    labelLarge = labelLarge.copy(fontFamily = Sans), labelMedium = labelMedium.copy(fontFamily = Sans),
    labelSmall = labelSmall.copy(fontFamily = Sans),
)

/**
 * Material's scheme, filled from the palette: neutral surfaces, Pulse as the
 * primary (the one accent: a switch that is on, a focused field), no tint.
 */
@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    val p = Tone.palette
    val scheme = (if (p.light) lightColorScheme() else darkColorScheme()).copy(
        background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.text2,
        surfaceTint = Color.Transparent, surfaceBright = p.surface2, surfaceDim = p.bg,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2,
        primary = p.pulse, onPrimary = Tone.ink, primaryContainer = p.surface2, onPrimaryContainer = p.text,
        secondary = p.text2, onSecondary = p.bg, secondaryContainer = p.surface2, onSecondaryContainer = p.text,
        tertiary = p.text2, onTertiary = p.bg, tertiaryContainer = p.surface2, onTertiaryContainer = p.text,
        outline = p.strokeStrong, outlineVariant = p.stroke,
        error = p.fail, onError = Tone.ink, errorContainer = p.fail.copy(alpha = 0.16f), onErrorContainer = p.fail,
        inverseSurface = p.text, inverseOnSurface = p.bg, inversePrimary = p.pulse, scrim = Color.Black,
    )
    MaterialTheme(colorScheme = scheme, typography = androidx.compose.material3.Typography().inSans(), content = content)
}
