package com.erelay.relay.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The few kinds of touch the lists and menus answer with, named for what
 * they mean rather than for the constant behind them.
 *
 * Through the View rather than Compose's HapticFeedback: that offers a
 * handful of types and maps the rest to a long press, and the point of a
 * detent or a segment tick is that it is lighter than one. The View also
 * honours the phone's own touch-feedback setting, so a phone set to no
 * vibration gets none; Look and feel's switch is honoured on top of it.
 *
 * The newer constants are only asked for where they exist: Android 14 has
 * ticks made for exactly this (a wheel's detent, a segment), 11 has a
 * confirm, and below those the nearest older one stands in.
 */
@Stable
class Haptics internal constructor(private val view: View, private val on: Boolean) {

    /** A row passing under the thumb while the list moves: the lightest there is. */
    fun detent() = buzz(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        else HapticFeedbackConstants.CLOCK_TICK)

    /** Something opened, closed or turned over: a country's cities, a menu. */
    fun tick() = buzz(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
        else HapticFeedbackConstants.CLOCK_TICK)

    /** A choice landed that does not commit to anything yet. */
    fun select() = buzz(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
        else HapticFeedbackConstants.CONTEXT_CLICK)

    /** A switch thrown - a star on or off. */
    fun toggle(on: Boolean) = buzz(when {
        Build.VERSION.SDK_INT >= 34 ->
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        else -> HapticFeedbackConstants.CLOCK_TICK
    })

    /** The choice that does something: the row that connects, the option taken. */
    fun confirm() = buzz(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY)

    /** A choice that cannot be taken: a list that is already full. */
    fun refuse() = buzz(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS)

    private fun buzz(kind: Int) {
        if (on) view.performHapticFeedback(kind)
    }
}

/** The haptics for this screen, off when Look and feel says so. */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val on = LocalFeel.current.haptics
    return remember(view, on) { Haptics(view, on) }
}
