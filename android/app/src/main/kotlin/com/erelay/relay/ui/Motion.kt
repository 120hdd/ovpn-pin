package com.erelay.relay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.erelay.relay.ui.theme.Shape
import kotlinx.coroutines.delay

/**
 * Whether the phone has animations switched off (Developer options, or
 * Remove animations): the animator duration scale is 0. Then nothing fades;
 * it just swaps.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        try {
            android.provider.Settings.Global.getFloat(context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Rises into place: fades in while travelling up a short way, after
 * [delayMs]. Given increasing delays, a column of these arrives one after
 * another rather than all at once - the entrance of the welcome screens.
 * Runs once, when first shown.
 */
@Composable
fun Rise(delayMs: Int, modifier: Modifier = Modifier, distance: Float = 28f, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        p.animateTo(1f, tween(640, easing = Shape.swing))
    }
    Box(modifier.graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * distance.dp.toPx()
        val s = 0.96f + 0.04f * p.value
        scaleX = s
        scaleY = s
    }) { content() }
}
