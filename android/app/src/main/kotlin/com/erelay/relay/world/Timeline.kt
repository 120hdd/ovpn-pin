package com.erelay.relay.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/*
 * A very small tween engine: just what the desktop's atlas.js asked of GSAP.
 * A [Timeline] is a list of things that start at a time and run for a while;
 * it is advanced by the frame's dt and nothing else drives it, so it is as
 * deterministic as the scene around it and needs no thread of its own.
 */

/** The curves in use, named as GSAP names them (in / out / inOut, and the power). */
object Ease {
    val linear: (Float) -> Float = { it }
    val power1In: (Float) -> Float = { it }
    val power1Out: (Float) -> Float = { it }
    val power1InOut: (Float) -> Float = { inOut(it, 2) }
    val power2Out: (Float) -> Float = { out(it, 2) }
    val power2InOut: (Float) -> Float = { inOut(it, 2) }
    val power3InOut: (Float) -> Float = { inOut(it, 3) }
    val sineInOut: (Float) -> Float = { -(cos(PI.toFloat() * it) - 1f) / 2f }
    val expoInOut: (Float) -> Float = {
        when {
            it <= 0f -> 0f
            it >= 1f -> 1f
            it < 0.5f -> 0.5f * 2f.pow(20f * it - 10f)
            else -> 1f - 0.5f * 2f.pow(-20f * it + 10f)
        }
    }

    private fun out(t: Float, n: Int) = 1f - (1f - t).pow(n)
    private fun inOut(t: Float, n: Int) =
        if (t < 0.5f) 0.5f * (2f * t).pow(n) else 1f - 0.5f * (2f * (1f - t)).pow(n)
}

class Timeline {
    private class Item(
        val at: Float,
        val dur: Float,
        val ease: (Float) -> Float,
        val begin: (() -> Unit)?,
        val apply: ((Float) -> Unit)?,
    ) {
        var started = false
        var done = false
    }

    private val items = ArrayList<Item>()
    private var time = 0f
    private var killed = false

    /** Where the last-finishing tween ends: what "after everything so far" means. */
    var end = 0f
        private set

    /** Every item has run to its end. */
    val done: Boolean get() = killed || items.all { it.done }

    /** Stops it where it is: nothing of it runs again. */
    fun kill() { killed = true }

    /**
     * Runs [apply] with the eased 0..1 progress from [at] for [dur] seconds.
     * [begin] runs once, as it starts: that is where a tween reads the value it
     * leaves from, as GSAP does, so tweens queued behind others see where
     * those left things.
     */
    fun tween(
        at: Float, dur: Float, ease: (Float) -> Float = Ease.power2InOut,
        begin: (() -> Unit)? = null, apply: (Float) -> Unit,
    ): Timeline {
        items += Item(at, dur, ease, begin, apply)
        end = maxOf(end, at + dur)
        return this
    }

    /** One number carried from wherever it is when the tween starts to [to]. */
    fun anim(
        at: Float, dur: Float, ease: (Float) -> Float = Ease.power2InOut,
        get: () -> Float, set: (Float) -> Unit, to: Float,
    ): Timeline {
        var from = 0f
        return tween(at, dur, ease, begin = { from = get() }) { p -> set(from + (to - from) * p) }
    }

    /** Something that happens once, at [at]. */
    fun call(at: Float, fn: () -> Unit): Timeline {
        items += Item(at, 0f, Ease.linear, null) { fn() }
        end = maxOf(end, at)
        return this
    }

    fun advance(dt: Float) {
        time += dt
        for (item in items.toTypedArray()) {
            if (killed) return
            if (item.done || time < item.at) continue
            if (!item.started) { item.started = true; item.begin?.invoke() }
            val p = if (item.dur <= 0f) 1f else ((time - item.at) / item.dur).coerceIn(0f, 1f)
            item.apply?.invoke(item.ease(p))
            if (p >= 1f) item.done = true
        }
    }

    /**
     * Everything to its end state at once, in the order it would have
     * happened: what a phone with animations switched off sees instead of a
     * journey.
     */
    fun finish() {
        for (item in items.sortedBy { it.at }) {
            if (killed) return
            if (item.done) continue
            if (!item.started) { item.started = true; item.begin?.invoke() }
            item.apply?.invoke(item.ease(1f))
            item.done = true
        }
    }
}
