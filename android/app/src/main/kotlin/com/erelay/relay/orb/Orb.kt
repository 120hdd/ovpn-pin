package com.erelay.relay.orb

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.erelay.relay.ui.press
import com.erelay.relay.data.Mode
import com.erelay.relay.ui.theme.toneOf
import kotlin.math.cos
import kotlin.math.sin

/**
 * The hero's stage: the sphere, and the four CSS layers the desktop draws
 * around its canvas - a breathing glow, a pulsing halo, and two orbit rings
 * each carrying a bead in the state's colour. Sizes are the desktop's pixels
 * as dp: a 210 stage, a 190x184 and a 163x170 ring, a 145 halo.
 *
 * [flow] is the traffic pace, 0..1, which the beads swell with.
 */
@Composable
fun OrbStage(
    mode: Mode, flow: Float, paused: Boolean, modifier: Modifier = Modifier, size: Dp = 210.dp,
    still: Boolean = false, onTap: (() -> Unit)? = null, onHold: (() -> Unit)? = null,
) {
    val tone = toneOf(mode)
    val accent by animateColorAsState(tone.accent, tween(460), label = "accent")
    val glow by animateColorAsState(tone.glow, tween(460), label = "glow")

    val loop = rememberInfiniteTransition(label = "orb")
    val breathe = loop.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2900, easing = LinearEasing), RepeatMode.Reverse), label = "breathe")
    val halo = loop.animateFloat(0f, 1f,
        infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "halo")
    val outer = loop.animateFloat(0f, 360f,
        infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "outer")
    val inner = loop.animateFloat(360f, 0f,
        infiniteRepeatable(tween(13000, easing = LinearEasing)), label = "inner")

    val tapSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val held by tapSource.collectIsPressedAsState()
    val squish by androidx.compose.animation.core.animateFloatAsState(
        if (held) 0.93f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.45f,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow), label = "squish")

    Box(
        modifier.size(size).graphicsLayer { scaleX = squish; scaleY = squish },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val unit = this.size.width / 210f
            // Still: the rings and glow hold one pose with the sphere.
            val breathe = if (still) 0.5f else breathe.value
            val halo = if (still) 0.35f else halo.value

            // The glow: a 150 disc of --blob-glow, breathing between 0.88 and
            // 1.13. A radial falloff stands in for the CSS blur.
            val scale = 0.88f + 0.25f * breathe
            val alpha = (0.68f + 0.28f * breathe) * 0.5f
            drawCircle(
                Brush.radialGradient(
                    listOf(glow.copy(alpha = glow.alpha * alpha), Color.Transparent),
                    center = center, radius = 120f * unit * scale),
                radius = 120f * unit * scale)

            // The halo: a 145 ring that grows and fades, peaking at 0.3.
            val hs = 0.82f + 0.48f * halo
            val ha = if (halo < 0.5f) halo / 0.5f * 0.3f else (1 - halo) / 0.5f * 0.3f
            drawCircle(accent.copy(alpha = ha), radius = 72.5f * unit * hs, style = Stroke(1f * unit))

            ring(190f * unit, 184f * unit, -12f, if (still) 210f else outer.value, 1f, accent, flow, unit)
            ring(163f * unit, 170f * unit, 0f, if (still) 40f else inner.value, 0.62f, accent, flow, unit)
        }

        val colors = remember(mode, com.erelay.relay.ui.theme.Tone.signal) {
            floatArrayOf(
                tone.a.red, tone.a.green, tone.a.blue,
                tone.b.red, tone.b.green, tone.b.blue,
                tone.c.red, tone.c.green, tone.c.blue)
        }
        AndroidView(
            factory = { OrbView(it) },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                v.mode = mode
                v.colors = colors
                v.still = still
                v.paused = paused
            },
        )

        // The sphere is a button too: the same press as Connect, on the body
        // itself rather than the whole stage, so a thumb resting on the empty
        // ring space does not start a tunnel. It squishes under the finger.
        if (onTap != null) {
            Box(
                Modifier
                    .size(size * 0.64f)
                    .orbPress(tapSource, onTap, onHold)
                    .semantics { contentDescription = when (mode) {
                        Mode.ON -> "Disconnect"
                        Mode.BUSY -> "Cancel"
                        else -> "Connect"
                    } },
            )
        }
    }
}

/** One orbit ring, an ellipse turned by [tilt], with its bead at [angle]. */
private fun DrawScope.ring(
    w: Float, h: Float, tilt: Float, angle: Float, alpha: Float,
    accent: Color, flow: Float, unit: Float,
) {
    rotate(tilt) {
        drawOval(Color.White.copy(alpha = 0.105f * alpha),
            topLeft = Offset(center.x - w / 2, center.y - h / 2), size = Size(w, h),
            style = Stroke(1f * unit))
        val rad = Math.toRadians(angle.toDouble())
        val bead = Offset(center.x + (w / 2) * cos(rad).toFloat(), center.y + (h / 2) * sin(rad).toFloat())
        val r = (3f + flow * 1.25f) * unit
        val glowR = (11f + flow * 15f) * unit / 2
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f * alpha), Color.Transparent),
            center = bead, radius = glowR + r), radius = glowR + r, center = bead)
        drawCircle(accent.copy(alpha = alpha), radius = r, center = bead)
    }
}

/**
 * The sphere's own touch: a tap is the Connect button, a hold (while
 * connected) is reconnect - a fresh race to a fresh exit, without the two
 * presses of disconnect-then-connect. Each gets its own haptic, so a hold
 * that landed is felt as different from a tap.
 */
private fun Modifier.orbPress(
    source: androidx.compose.foundation.interaction.MutableInteractionSource,
    onTap: () -> Unit,
    onHold: (() -> Unit)?,
): Modifier = composed {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val buzz = com.erelay.relay.ui.LocalFeel.current.haptics
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    combinedClickable(
        interactionSource = source,
        indication = null,
        onLongClick = onHold?.let { hold ->
            {
                if (buzz) haptics.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                hold()
            }
        },
        onClick = {
            if (buzz) haptics.performHapticFeedback(
                androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
            onTap()
        },
    )
}
