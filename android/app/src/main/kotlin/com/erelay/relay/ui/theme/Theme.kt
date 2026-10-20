package com.erelay.relay.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
 * The desktop's palette, `app/ui/app.css` in its dark theme, name for name.
 * A colour changed there should be findable here by searching for the word.
 */
object Tone {
    val bg = Color(0xFF09090E)
    val surface = Color(0xFF17171F)
    val control = Color(0xFF20212B)
    val controlHover = Color(0xFF292B37)
    val controlActive = Color(0xFF313440)
    val divider = Color(0x17FFFFFF)
    val border = Color(0x21FFFFFF)

    val text = Color(0xFFF5F5F8)
    val textMuted = Color(0xFFB9BAC6)
    val textOff = Color(0xFF777987)

    val ok = Color(0xFF61EFBF)
    val work = Color(0xFFFFC761)
    val fail = Color(0xFFFF8F9B)
    val failBg = Color(0x80651D2C)
    val focus = Color(0xFF74E4C2)
    val accent = Color(0xFF8C82FF)

    val jade2 = Color(0xFF4AE5C3)
    val jadeDeep = Color(0xFF0B6B52)
    val ink = Color(0xFF06281D)

    val fluxDown = Color(0xFF61EFBF)
    val fluxUp = Color(0xFF9C8CFF)

    val surfshark = Color(0xFF3BC1C9)
    val windscribe = Color(0xFF61EFBF)
}

/** The orb's colours per state: `--blob-a/b/c`, `--blob-glow`, `--state-accent`. */
data class StateTone(val a: Color, val b: Color, val c: Color, val glow: Color, val accent: Color)

fun toneOf(mode: Mode): StateTone = when (mode) {
    Mode.OFF -> StateTone(Color(0xFF9C8CFF), Color(0xFF4CE2D2), Color(0xFF483AC2),
        Color(102, 90, 231, (0.48f * 255).toInt()), Color(0xFF8C82FF))
    Mode.BUSY -> StateTone(Color(0xFFFFCB67), Color(0xFFFF778E), Color(0xFF9B4BDA),
        Color(255, 156, 83, (0.42f * 255).toInt()), Color(0xFFFFC761))
    Mode.ON -> StateTone(Color(0xFF71F1BD), Color(0xFF3DD4EE), Color(0xFF087E6A),
        Color(48, 218, 171, (0.42f * 255).toInt()), Color(0xFF61EFBF))
    Mode.FAIL -> StateTone(Color(0xFFFF8495), Color(0xFFB968E7), Color(0xFF8B2849),
        Color(223, 70, 106, (0.36f * 255).toInt()), Color(0xFFFF8F9B))
}

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
    val eyebrow = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 10.sp,
        letterSpacing = 1.4.sp, color = Tone.textOff)
    val kicker = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W700, fontSize = 10.sp,
        letterSpacing = 1.6.sp, color = Tone.ok)
    val title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W700, fontSize = 20.sp,
        color = Tone.text)
    val status = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W700, fontSize = 20.sp,
        letterSpacing = 0.4.sp, color = Tone.text)
    val body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 14.sp,
        lineHeight = 20.sp, color = Tone.textMuted)
    val strong = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 15.sp,
        color = Tone.text)
    val small = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 12.5.sp,
        lineHeight = 18.sp, color = Tone.textOff)
    val mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp,
        color = Tone.textOff)
    val ip = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 16.sp,
        color = Tone.text)
    val button = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 15.sp,
        color = Tone.text)
}

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
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
