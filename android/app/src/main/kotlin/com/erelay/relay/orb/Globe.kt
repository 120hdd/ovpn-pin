package com.erelay.relay.orb

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
 * The hologram globe, whole and small, in the middle of a square [size]: the
 * welcome and setup screens' mark. It draws nothing it cannot be asked about:
 * no touch, no sound. Its line colour is the state's ([toneOf]), eased on the
 * render thread; [still] and the system's reduced motion give one frame and no
 * turn at all; [paused] stops drawing altogether. [flow] is not used any more
 * (there is no pace to show); it stays so those callers need no change.
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
    GlobeLayer(mode, paused, place, still || reduced, modifier.size(size), toneOverride)
}

@Composable
private fun GlobeLayer(
    mode: Mode, paused: Boolean, place: FloatArray, still: Boolean,
    modifier: Modifier, toneOverride: StateTone? = null,
) {
    val line = (toneOverride ?: toneOf(mode)).line
    val rgb = remember(line) { line.rgb() }
    val light = Tone.light
    AndroidView(
        factory = { GlobeView(it) },
        modifier = modifier,
        update = { v ->
            v.mode = mode
            v.color = rgb
            v.light = light
            v.still = still
            v.place = place
            v.paused = paused
        },
    )
}
