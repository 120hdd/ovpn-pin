package com.erelay.relay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.erelay.relay.ui.theme.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Relay's mark: a disc, the ring around it open across the upper right, and
 * the bead that has left through the opening. The same geometry as the
 * launcher icon (res/drawable/ic_launcher_fg.xml), on its 256 grid.
 */
private object Mark {
    const val GRID = 256f
    const val DISC = 56f
    const val RING = 100f
    const val RING_WIDTH = 20f
    /** The ring runs clockwise from just above three o'clock round to just right of twelve. */
    const val RING_START = -10f
    const val RING_SWEEP = 290f
    const val BEAD = 16f
    /** The bead sits on the opening's bisector, a little outside the ring. */
    const val BEAD_ANGLE = -45f
    const val BEAD_OUT = 132f

    val disc get() = if (Tone.light) Color(0xFF12B886) else Color(0xFF61EFBF)
    val ring get() = if (Tone.light) Color(0xFF111318) else Color(0xFFFFFFFF)
}

/**
 * The mark, still - or drawing itself in when [animate] is set: the ring
 * traced round from its start, the disc swelling in its middle, then the
 * bead leaving through the gap. Once, when first shown.
 */
@Composable
fun RelayMark(modifier: Modifier = Modifier, animate: Boolean = false) {
    val ring = remember { Animatable(if (animate) 0f else 1f) }
    val disc = remember { Animatable(if (animate) 0f else 1f) }
    val bead = remember { Animatable(if (animate) 0f else 1f) }
    if (animate) LaunchedEffect(Unit) {
        launch { ring.animateTo(1f, tween(2000, easing = FastOutSlowInEasing)) }
        launch {
            delay(550)
            disc.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessVeryLow))
        }
        delay(1850)
        bead.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val discColor = Mark.disc
    val ringColor = Mark.ring
    Canvas(modifier) {
        val k = size.minDimension / Mark.GRID
        val c = Offset(size.width / 2, size.height / 2)

        val r = Mark.DISC * k * disc.value
        if (r > 0f) drawCircle(discColor, r, c)

        if (ring.value > 0f) {
            val rr = Mark.RING * k
            drawArc(ringColor, Mark.RING_START, Mark.RING_SWEEP * ring.value, useCenter = false,
                topLeft = Offset(c.x - rr, c.y - rr), size = Size(rr * 2, rr * 2),
                style = Stroke(Mark.RING_WIDTH * k, cap = StrokeCap.Round))
        }

        if (bead.value > 0f) {
            // Out from the ring through the opening to where it rests, growing as it goes.
            val t = bead.value
            val d = (Mark.RING + (Mark.BEAD_OUT - Mark.RING) * t) * k
            val a = Math.toRadians(Mark.BEAD_ANGLE.toDouble())
            val at = Offset(c.x + d * cos(a).toFloat(), c.y + d * sin(a).toFloat())
            drawCircle(ringColor, Mark.BEAD * k * (0.4f + 0.6f * t), at, alpha = t)
        }
    }
}
