package com.erelay.relay.orb

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.erelay.relay.data.Mode
import com.erelay.relay.ui.rememberReducedMotion
import com.erelay.relay.ui.theme.StateTone
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.toneOf

private fun Color.rgb() = floatArrayOf(red, green, blue)

/**
 * The Home screen's backdrop: the hologram globe, centred on the right edge
 * of its box so about half of it shows, large, and quiet enough that the
 * words and controls on top stay readable (the shader thins it toward the left
 * and the bottom). It draws nothing it cannot be asked about: no touch, no
 * sound.
 *
 * Its line colour is the state's ([toneOf]) and is eased on the render thread.
 * With a [country] (two letters, lower case) it turns to that country and
 * marks it; without one it just turns. [still] and the system's reduced motion
 * give one frame and no turn at all. [paused] stops drawing altogether.
 */
@Composable
fun GlobeBackdrop(
    mode: Mode, paused: Boolean, country: String, modifier: Modifier = Modifier, still: Boolean = false,
) {
    val reduced = rememberReducedMotion()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        // Diameter about 0.68 of the height, but never so wide that the globe
        // swallows a narrow phone: its left limb stays out of the left third.
        val r = (minOf(0.34f * h, 0.66f * w) / h).coerceAtLeast(0.05f)
        val place = remember(w, h) { floatArrayOf(1.01f, 0.47f, r) }
        // Steps back to 65% once connected, so the flag behind and the numbers
        // in front have the stage.
        val alpha by animateFloatAsState(if (mode == Mode.ON) 0.65f else 1f,
            tween(if (reduced) 0 else 333, easing = CubicBezierEasing(0f, 0f, 0f, 1f)), label = "globeAlpha")
        GlobeLayer(mode, paused, country, place, still || reduced,
            Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha })
    }
}

/**
 * The same globe, whole and small, in the middle of a square [size]: the
 * welcome and setup screens' mark. [flow] is not used any more (there is no
 * pace to show); it stays so those callers need no change.
 */
@Composable
fun OrbStage(
    mode: Mode, flow: Float, paused: Boolean, modifier: Modifier = Modifier, size: Dp = 120.dp,
    still: Boolean = false,
    /** Colours other than the state's own: the resting set on screens before a connection exists. */
    toneOverride: StateTone? = null,
) {
    val reduced = rememberReducedMotion()
    val place = remember { floatArrayOf(0.5f, 0.5f, 0.47f) }
    GlobeLayer(mode, paused, "", place, still || reduced, modifier.size(size), toneOverride)
}

@Composable
private fun GlobeLayer(
    mode: Mode, paused: Boolean, country: String, place: FloatArray, still: Boolean,
    modifier: Modifier, toneOverride: StateTone? = null,
) {
    val line = (toneOverride ?: toneOf(mode)).line
    val rgb = remember(line) { line.rgb() }
    val focus = remember(country) { if (country.length == 2) countryFocus(country) else null }
    val light = Tone.light
    AndroidView(
        factory = { GlobeView(it) },
        modifier = modifier,
        update = { v ->
            v.mode = mode
            v.color = rgb
            v.light = light
            v.still = still
            v.focus = focus
            v.place = place
            v.paused = paused
        },
    )
}
