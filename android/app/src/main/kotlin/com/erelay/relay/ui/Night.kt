package com.erelay.relay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import com.erelay.relay.ui.theme.script
import kotlinx.coroutines.delay

/*
 * The Night room's recurring parts (.claude/DESIGN-NIGHT.md 4): the page, a
 * Slate card, a floating glass layer, the one glowing active item, the live
 * dot, the chips, the toasts. Screens compose these rather than drawing their
 * own borders.
 */

/**
 * A floating layer - a dialog, a menu, a tooltip, a toast, the home panel: Ink
 * Glass with an edge that is brighter along the top. Compose cannot blur what
 * is behind a view, so the fill is the translucent glass over the map, or the
 * solid fallback ([solid], the default) over a plain page.
 */
fun Modifier.glass(
    shape: androidx.compose.ui.graphics.Shape,
    solid: Boolean = true,
): Modifier = this
    .clip(shape)
    .background(if (solid) Tone.glassSolid else Tone.glass)
    .border(1.dp, Brush.verticalGradient(0f to Tone.glassTop, 0.4f to Tone.stroke, 1f to Tone.stroke), shape)

/** A floating layer's lift: a soft shadow on the white page; on the dark ones the glass edge does it alone. */
fun Modifier.floatShadow(shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (!Tone.light) this else shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f),
        spotColor = Color.Black.copy(alpha = 0.12f))

/**
 * The glow of the one active item: 0 0 32px -6px in Spotify Halo, drawn as
 * nested rounded rects each a few dp wider and fainter. [p] is how lit, 0..1.
 * Off on the white page, where the neon is not used.
 */
private fun DrawScope.halo(p: Float, rect: RoundRect) {
    if (p <= 0.01f || !Tone.glows) return
    val step = 3.dp.toPx()
    for (i in 6 downTo 1) {
        val d = step * i
        val path = Path().apply {
            addRoundRect(RoundRect(rect.left - d, rect.top - d, rect.right + d, rect.bottom + d,
                CornerRadius(rect.topLeftCornerRadius.x + d), CornerRadius(rect.topRightCornerRadius.x + d),
                CornerRadius(rect.bottomRightCornerRadius.x + d), CornerRadius(rect.bottomLeftCornerRadius.x + d)))
        }
        drawPath(path, Tone.halo.copy(alpha = Tone.halo.alpha * 0.17f * p * (1.15f - i / 7f)))
    }
}

/** The active fill and edge: rgba(30,215,96,.24) -> clear inside, a 1dp Pulse -> clear border on top. */
private fun DrawScope.activeFace(p: Float, outline: Path, edge: Path) {
    if (p <= 0.01f) return
    val fill = Brush.linearGradient(
        0f to Tone.pulse.copy(alpha = (if (Tone.light) 0.12f else 0.24f) * p), 1f to Color.Transparent,
        start = Offset.Zero, end = Offset(size.width, size.height))
    drawPath(outline, fill)
    val border = Brush.linearGradient(
        0f to Tone.pulse.copy(alpha = p), 0.85f to Color.Transparent,
        start = Offset.Zero, end = Offset(size.width, size.height))
    drawPath(edge, border, style = Stroke(1.dp.toPx()))
}

/** The 3x24dp Pulse bar on an item's outer (leading) edge, centred on [cy]. */
private fun DrawScope.edgeBar(alpha: Float, cy: Float) {
    if (alpha <= 0.01f) return
    val bh = 24.dp.toPx()
    val bw = 3.dp.toPx()
    val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - bw else 0f
    drawRoundRect(Tone.pulse.copy(alpha = alpha), Offset(x, cy - bh / 2), Size(bw, bh), CornerRadius(bw / 2))
}

/**
 * A Slate card: solid, 1dp Edge, no shadow. With [active] it is the one active
 * item on the screen: a halo behind it, a 1dp gradient border Pulse -> clear,
 * an inner fill rgba(30,215,96,.24) -> clear, and a 3x24dp Pulse bar on its
 * outer edge (at [barY] from the top, or the middle). The change is animated;
 * with the system's animator scale at zero it is instant.
 *
 * The halo is drawn outside the card, so keep clipping off the modifiers before it.
 */
fun Modifier.slateCard(
    radius: Dp = Shape.card,
    active: Boolean = false,
    barY: Dp = Dp.Unspecified,
): Modifier = composed {
    val reduced = rememberReducedMotion()
    val p by animateFloatAsState(if (active) 1f else 0f,
        tween(if (reduced) 0 else 300, easing = Shape.ease), label = "active")
    slateSeg(Seg.WHOLE, radius, p, barY)
}

/** Which part of a card cut across list items a modifier draws: see [slateSeg]. */
enum class Seg { WHOLE, HEAD, BODY, FOOT }

/** The part of one card that row [i] of [n] is, for a list drawn as a single card. */
fun segOf(i: Int, n: Int): Seg = when {
    n <= 1 -> Seg.WHOLE
    i == 0 -> Seg.HEAD
    i == n - 1 -> Seg.FOOT
    else -> Seg.BODY
}

/**
 * One part of a Slate card cut across list items, so a drawer's rows can stay
 * items of their own in a lazy list: [Seg.HEAD] has the top corners,
 * [Seg.FOOT] the bottom ones, [Seg.BODY] only the side edges, [Seg.WHOLE] all
 * four. [lit] (0..1) lights it as the active item - the halo, the gradient
 * face and the edge bar; only the head or a whole card is ever lit.
 */
fun Modifier.slateSeg(seg: Seg, radius: Dp = Shape.card, lit: Float = 0f, barY: Dp = Dp.Unspecified): Modifier =
    drawWithCache {
        val r = radius.toPx()
        val w = 1.dp.toPx()
        val top = seg == Seg.WHOLE || seg == Seg.HEAD
        val bottom = seg == Seg.WHOLE || seg == Seg.FOOT
        val tr = if (top) r else 0f
        val br = if (bottom) r else 0f
        val shapeRect = RoundRect(0f, 0f, size.width, size.height,
            CornerRadius(tr), CornerRadius(tr), CornerRadius(br), CornerRadius(br))
        val outline = Path().apply { addRoundRect(shapeRect) }
        // The Edge: round the corners this part has, straight down the sides it shares.
        val h = size.height
        val x0 = w / 2
        val x1 = size.width - w / 2
        val rr = (r - w / 2).coerceAtLeast(0f)
        val edge = Path().apply {
            when {
                seg == Seg.WHOLE -> addRoundRect(RoundRect(x0, x0, x1, h - x0, CornerRadius(rr)))
                seg == Seg.HEAD -> {
                    moveTo(x0, h)
                    lineTo(x0, x0 + rr)
                    arcTo(Rect(x0, x0, x0 + 2 * rr, x0 + 2 * rr), 180f, 90f, false)
                    lineTo(x1 - rr, x0)
                    arcTo(Rect(x1 - 2 * rr, x0, x1, x0 + 2 * rr), 270f, 90f, false)
                    lineTo(x1, h)
                }
                seg == Seg.FOOT -> {
                    moveTo(x0, 0f)
                    lineTo(x0, h - x0 - rr)
                    arcTo(Rect(x0, h - x0 - 2 * rr, x0 + 2 * rr, h - x0), 180f, -90f, false)
                    lineTo(x1 - rr, h - x0)
                    arcTo(Rect(x1 - 2 * rr, h - x0 - 2 * rr, x1, h - x0), 90f, -90f, false)
                    lineTo(x1, 0f)
                }
                else -> {
                    moveTo(x0, 0f); lineTo(x0, h)
                    moveTo(x1, 0f); lineTo(x1, h)
                }
            }
        }
        onDrawWithContent {
            halo(lit, shapeRect)
            drawPath(outline, Tone.surface)
            drawPath(edge, Tone.stroke, style = Stroke(w))
            activeFace(lit, outline, edge)
            drawContent()
            edgeBar(lit, if (barY == Dp.Unspecified) h / 2 else barY.toPx())
        }
    }

/** The 3x24dp Pulse bar a picked row carries on its outer edge: selected, with no glow. */
fun Modifier.pickedBar(on: Boolean): Modifier = if (!on) this else drawWithContent {
    drawContent()
    edgeBar(1f, size.height / 2)
}

/**
 * The live dot: 8dp Pulse, with a halo ring that grows 1 -> 2.4x while it
 * fades 0.6 -> 0 over 1.4s, forever. Still when animations are off.
 */
@Composable
fun LiveDot(modifier: Modifier = Modifier, color: Color = Tone.pulse, size: Dp = 8.dp, ring: Boolean = true) {
    val reduced = rememberReducedMotion()
    if (!ring || reduced) {
        Box(modifier.size(size).drawBehind { drawCircle(color) })
        return
    }
    val t = rememberInfiniteTransition(label = "live")
    val k by t.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing), RepeatMode.Restart), label = "ring")
    Box(modifier.size(size).drawBehind {
        drawCircle(color.copy(alpha = 0.6f * (1f - k)), radius = this.size.minDimension / 2 * (1f + 1.4f * k))
        drawCircle(color)
    })
}

/** A count badge: outlined 1dp Edge, 12 type. */
@Composable
fun CountBadge(text: String, modifier: Modifier = Modifier, active: Boolean = false) {
    Text(text, style = Type.mono.copy(color = if (active) Tone.text else Tone.text3),
        maxLines = 1, softWrap = false,
        modifier = modifier
            .border(1.dp, if (active) Tone.strokeStrong else Tone.stroke, RoundedCornerShape(Shape.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp))
}

/** A chip: a Raised Slate pill. */
@Composable
fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(Shape.pill)).background(Tone.surface2).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) { content() }
}

/** The Raised Slate circle an icon sits in (40dp by default); Pulse ink when it belongs to the active item. */
@Composable
fun IconWell(icon: ImageVector, modifier: Modifier = Modifier, active: Boolean = false, size: Dp = 40.dp,
             tint: Color = Tone.text2) {
    Box(modifier.size(size).clip(CircleShape).background(Tone.surface2), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = if (active) Tone.pulse else tint)
    }
}

// -- the page ---------------------------------------------------------------------------------

/**
 * Film grain: a small tile of single-pixel specks, half light and half dark,
 * each at its own strength, made once and repeated across the page. Drawn
 * faintly, the dark specks show on the white page and the light ones on the
 * dark pages.
 */
private val GRAIN: ImageBitmap by lazy {
    val n = 128
    val rnd = java.util.Random(7L)
    val px = IntArray(n * n) {
        val a = rnd.nextInt(256)
        val v = if (rnd.nextBoolean()) 255 else 0
        (a shl 24) or (v shl 16) or (v shl 8) or v
    }
    android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * The page (DESIGN-NIGHT 1): the theme's bg, a soft cone of light falling
 * from the top centre, and the grain over both. Built once per size and theme.
 */
fun Modifier.nightPage(): Modifier = drawWithCache {
    val bg = Tone.bg
    val light = Tone.light
    val cone = Brush.radialGradient(
        0f to Color.White.copy(alpha = if (light) 0.75f else 0.055f),
        0.55f to Color.White.copy(alpha = if (light) 0.25f else 0.018f),
        1f to Color.Transparent,
        center = Offset(size.width / 2f, -size.height * 0.08f),
        radius = maxOf(size.width, size.height) * 0.75f,
    )
    val grain = ShaderBrush(ImageShader(GRAIN, TileMode.Repeated, TileMode.Repeated))
    onDrawBehind {
        drawRect(bg)
        drawRect(cone)
        drawRect(grain, alpha = if (light) 0.035f else 0.03f)
    }
}

// -- toasts -----------------------------------------------------------------------------------

/**
 * The app's own toasts: a short line on a glass pill near the foot of the
 * screen, instead of the system's grey box. [show] works from anywhere; the
 * newest line replaces the one showing.
 */
object Toasts {
    data class Line(val text: String, val long: Boolean, val id: Long)

    var current by mutableStateOf<Line?>(null)
        private set
    private var next = 0L

    fun show(text: String, long: Boolean = false) {
        if (text.isBlank()) return
        current = Line(text, long, next++)
    }

    internal fun clear(id: Long) {
        if (current?.id == id) current = null
    }
}

/** Where [Toasts] are drawn: last in the root, over everything. */
@Composable
fun ToastHost() {
    val line = Toasts.current
    val reduced = rememberReducedMotion()
    LaunchedEffect(line?.id) {
        val l = line ?: return@LaunchedEffect
        delay(if (l.long) 3500L else 2000L)
        Toasts.clear(l.id)
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 96.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.BottomCenter) {
        AnimatedContent(line, transitionSpec = {
            (fadeIn(tween(if (reduced) 0 else 220, easing = Shape.ease)) +
                slideInVertically(tween(if (reduced) 0 else 320, easing = Shape.ease)) { it / 3 }) togetherWith
                fadeOut(tween(if (reduced) 0 else 160))
        }, label = "toast") { l ->
            if (l != null) {
                val shape = RoundedCornerShape(Shape.pill)
                Text(l.text, style = Type.body.copy(color = Tone.text, textAlign = TextAlign.Center).script(l.text),
                    maxLines = 3,
                    modifier = Modifier.widthIn(max = 420.dp).floatShadow(shape).glass(shape)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}
