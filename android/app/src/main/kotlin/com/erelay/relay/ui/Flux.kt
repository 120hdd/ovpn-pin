package com.erelay.relay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.data.Traffic
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The meter under the orb: how much has gone each way, and how fast now.
 *
 * Sixty readings, one a second, each smoothed (EMA 0.66) and drawn as a
 * monotone cubic that scrolls by a fraction of a sample every frame, so it
 * moves rather than ticks. Download takes the upper 62% in mint, upload
 * hangs below it in violet. Each band's scale climbs fast and settles slow,
 * with a floor, so a quiet line is a flat line rather than a magnified one.
 * The desktop's flux, app.js, number for number.
 */
private class Wave {
    val down = FloatArray(N)
    val up = FloatArray(N)
    var smoothDown = 0f
    var smoothUp = 0f
    var scaleDown = FLOOR_DOWN
    var scaleUp = FLOOR_UP
    var pushedAt = 0L

    fun push(t: Traffic, now: Long) {
        smoothDown += (t.downRate - smoothDown) * 0.66f
        smoothUp += (t.upRate - smoothUp) * 0.66f
        System.arraycopy(down, 1, down, 0, N - 1)
        System.arraycopy(up, 1, up, 0, N - 1)
        down[N - 1] = smoothDown
        up[N - 1] = smoothUp
        pushedAt = now
    }

    /** Fast up (k=3), slow down (k=0.35), never under the floor. */
    fun settle(dt: Float) {
        fun ease(scale: Float, peak: Float, floor: Float): Float {
            val want = max(peak * 1.15f, floor)
            val k = if (want > scale) 3f else 0.35f
            return scale + (want - scale) * (1 - exp(-dt * k))
        }
        scaleDown = ease(scaleDown, down.max(), FLOOR_DOWN)
        scaleUp = ease(scaleUp, up.max(), FLOOR_UP)
    }

    companion object {
        const val N = 60
        const val FLOOR_DOWN = 32f * 1024
        const val FLOOR_UP = 8f * 1024
    }
}

/** 0..1 for how busy the line is: the beads around the orb swell with it. */
fun paceOf(t: Traffic): Float {
    val bps = (t.downRate + t.upRate).toFloat()
    if (bps < 512) return 0f
    return min(1f, (kotlin.math.ln(bps / 512f) / kotlin.math.ln(4096f)))
}

@Composable
fun Flux(traffic: Traffic, modifier: Modifier = Modifier) {
    val wave = remember { Wave() }
    var frame by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(traffic) {
        wave.push(traffic, System.nanoTime())
    }
    // Sub-sample scrolling: how far past the last reading we are, 0..1.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                wave.settle(min(dt, 0.1f))
                frame = min(1f, (System.nanoTime() - wave.pushedAt) / 1e9f)
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            val f = frame
            val mid = size.height * 0.62f
            band(wave.down, wave.scaleDown, f, top = 0f, base = mid, up = true, color = Tone.fluxDown)
            band(wave.up, wave.scaleUp, f, top = mid, base = size.height, up = false, color = Tone.fluxUp)
            // The left quarter fades out: old readings leave rather than drop off an edge.
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 0.24f to Color.Black,
                    endX = size.width),
                blendMode = BlendMode.DstIn)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyph.arrowDown, "Downloaded", Modifier.size(12.dp), tint = Tone.fluxDown)
                Spacer(Modifier.width(4.dp))
                Text(bytes(traffic.down), style = Type.mono.copy(color = Tone.text, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium))
                Spacer(Modifier.width(8.dp))
                Text(rate(traffic.downRate), style = Type.mono)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rate(traffic.upRate), style = Type.mono)
                Spacer(Modifier.width(8.dp))
                Text(bytes(traffic.up), style = Type.mono.copy(color = Tone.text, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium))
                Spacer(Modifier.width(4.dp))
                Icon(Glyph.arrowDown, "Uploaded", Modifier.size(12.dp).rotate(180f), tint = Tone.fluxUp)
            }
        }
    }
}

/**
 * One band of the wave. Points are placed a sample apart and shifted left by
 * [frac] of a sample; the curve through them is Fritsch–Carlson monotone, so
 * it never overshoots below zero between two quiet readings.
 */
private fun DrawScope.band(
    values: FloatArray, scale: Float, frac: Float,
    top: Float, base: Float, up: Boolean, color: Color,
) {
    val n = values.size
    val step = size.width / (n - 2)
    val xs = FloatArray(n) { (it - frac) * step }
    val span = base - top
    val ys = FloatArray(n) {
        val h = min(1f, values[it] / scale) * span * 0.92f
        if (up) base - h else top + h
    }

    // Fritsch–Carlson tangents.
    val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
    val m = FloatArray(n)
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) {
        m[i] = if (d[i - 1] * d[i] <= 0) 0f else {
            val w1 = 2 * (xs[i + 1] - xs[i]) + (xs[i] - xs[i - 1])
            val w2 = (xs[i + 1] - xs[i]) + 2 * (xs[i] - xs[i - 1])
            (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
        }
    }

    val line = Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val h = xs[i + 1] - xs[i]
            cubicTo(xs[i] + h / 3, ys[i] + m[i] * h / 3,
                xs[i + 1] - h / 3, ys[i + 1] - m[i + 1] * h / 3,
                xs[i + 1], ys[i + 1])
        }
    }
    val fill = Path().apply {
        addPath(line)
        lineTo(xs[n - 1], base.takeIf { up } ?: top)
        lineTo(xs[0], base.takeIf { up } ?: top)
        close()
    }
    drawPath(fill, Brush.verticalGradient(
        if (up) listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.02f))
        else listOf(color.copy(alpha = 0.02f), color.copy(alpha = 0.28f)),
        startY = top, endY = base))
    drawPath(line, color.copy(alpha = 0.9f), style = Stroke(1.5.dp.toPx()))

    // The head: where the newest reading is, with a glow.
    val hx = xs[n - 1]
    val hy = ys[n - 1]
    if (hx in 0f..size.width + 1 && abs(hy - (if (up) base else top)) >= 0f) {
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.5f), Color.Transparent),
            center = Offset(hx, hy), radius = 8.dp.toPx()), 8.dp.toPx(), Offset(hx, hy))
        drawCircle(color, 2.5.dp.toPx(), Offset(hx, hy))
    }
}
