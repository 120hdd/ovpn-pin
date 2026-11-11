package com.erelay.relay.world

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import com.erelay.relay.data.Mode

/** Everything the scene is told by the screen around it, as one value so it arrives whole. */
class WorldInput(
    val mode: Mode,
    /** Where the traffic leaves now (two letters), or empty. */
    val live: String,
    /** The country picked in the list, or empty. */
    val picked: String,
    /** The country the user's own address is in, once known; empty before. */
    val home: String,
    val light: Boolean,
    /** Animations switched off: no journeys, no motion, still frames. */
    val calm: Boolean,
    /** The page's colour, r g b 0..1. */
    val bg: FloatArray,
    /** How far below the view's middle the country is meant to sit, as a fraction of its height. */
    val shift: Float,
)

/**
 * The home screen's world, on a surface of its own: the whole screen, behind
 * the words and controls.
 *
 * A TextureView rather than a GLSurfaceView, for the reason the old globe's
 * was: a SurfaceView is a hole punched through the window, so the sheets that
 * slide up over the main screen would be drawn under it; a TextureView is an
 * ordinary layer and composites like everything else.
 *
 * It draws on a thread of its own, one frame per display refresh while
 * something moves and about 30 a second while only the orbits and the blinking
 * dot do, and not at all while [paused] (a sheet over it, the app in the
 * background) or while animations are switched off and nothing changed. At
 * most the pixel density of a mid-range desktop's canvas is drawn (the buffer
 * is scaled to the view): lines and edges are smoothed in the shaders, so the
 * lower resolution costs nothing the eye can find, and the GPU does a half to a
 * third of the work.
 *
 * The scene itself ([Stage]) belongs to this view, not to its surface, so the
 * journey carries on where it was when the surface is made again.
 */
class WorldView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile var input: WorldInput? = null
        set(value) {
            val same = field === value
            field = value
            if (!same) render?.wake()
        }

    /** Covered by a sheet or the app in the background: stop drawing. */
    @Volatile var paused: Boolean = false
        set(value) {
            val same = field == value
            field = value
            if (!same) render?.wake()
        }

    private val world = WorldData.of(context)
    private var stage: Stage? = null
    private var render: RenderThread? = null

    init {
        surfaceTextureListener = this
    }

    /** The buffer is the view's size scaled down so it is never drawn at more than 1.75 pixels to the dp. */
    private fun scaleFor(): Float = minOf(1f, MAX_DENSITY / resources.displayMetrics.density)

    private fun sizeBuffer(surface: SurfaceTexture, w: Int, h: Int) {
        val s = scaleFor()
        surface.setDefaultBufferSize(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val w = world ?: return
        render?.quit()
        // Hidden until the first frame is in the texture, then faded up: setting up
        // EGL and compiling the shaders takes a few frames.
        animate().cancel()
        alpha = 0f
        sizeBuffer(surface, width, height)
        render = RenderThread(surface, w).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // The view has just told the texture it is as big as the view; this makes it smaller again.
        sizeBuffer(surface, width, height)
        render?.wake()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        render?.quit()
        render = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    /**
     * Draws once per display frame, on the display's own clock (a TextureView
     * does not pace whoever draws into it, so a plain loop would run as fast
     * as the GPU lets it and land unevenly against the screen's refresh).
     */
    private inner class RenderThread(
        private val texture: SurfaceTexture,
        private val data: WorldData,
    ) : Thread("world"), Choreographer.FrameCallback {

        @Volatile private var running = true
        @Volatile private var handler: android.os.Handler? = null
        private var choreographer: Choreographer? = null
        private var scheduled = false
        private var last = 0L
        private var drawn = 0L
        private var dirty = true
        private var shown = false
        private var applied: WorldInput? = null
        private var began = false

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var gl: WorldGl? = null
        private val size = IntArray(1)

        /** Start drawing again after a pause, or because something the picture shows changed. */
        fun wake() {
            handler?.post {
                dirty = true
                schedule()
            }
        }

        fun quit() {
            running = false
            handler?.post { android.os.Looper.myLooper()?.quit() }
            try { join(500) } catch (e: InterruptedException) { }
        }

        private fun schedule() {
            if (scheduled || !running || paused) return
            scheduled = true
            choreographer?.postFrameCallback(this)
        }

        override fun run() {
            android.os.Looper.prepare()
            try {
                if (!setUp()) return
                handler = android.os.Handler(android.os.Looper.myLooper()!!)
                // A quit() that came before there was a handler had nothing to post to.
                if (!running) return
                choreographer = Choreographer.getInstance()
                schedule()
                android.os.Looper.loop()
            } catch (e: Throwable) {
                // No GL, or a driver that refuses: the view stays blank and the page behind shows.
                Log.w("Relay", "world: ${e.message}")
            } finally {
                tearDown()
            }
        }

        /** A depth buffer for the borders to be tested against the planet; 4x multisampling when the GPU has it. */
        private fun setUp(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
            val config = pick(4) ?: pick(0) ?: return false
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false
            surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) return false
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) return false
            gl = WorldGl(this@WorldView.context, data)
            return gl!!.setUp()
        }

        private fun pick(samples: Int): EGLConfig? {
            val attrs = ArrayList<Int>().apply {
                addAll(listOf(EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16))
                if (samples > 0) addAll(listOf(EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, samples))
                add(EGL14.EGL_NONE)
            }.toIntArray()
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0)
            return if (count[0] > 0) configs[0] else null
        }

        private fun tearDown() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
            surface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            display = EGL14.EGL_NO_DISPLAY
            gl = null
        }

        private fun query(what: Int): Int {
            EGL14.eglQuerySurface(display, surface, what, size, 0)
            return size[0]
        }

        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            if (!running) return
            if (paused) {
                // Nothing is posted until wake(); the first frame back starts its clock afresh.
                last = 0L
                return
            }
            val input = this@WorldView.input
            val painter = gl
            if (input == null || painter == null) return

            // What is actually drawn into: the view's size scaled down, not the view's.
            val w = query(EGL14.EGL_WIDTH)
            val h = query(EGL14.EGL_HEIGHT)
            if (w <= 0 || h <= 0) { schedule(); return }

            val s = stage ?: Stage(data).also { stage = it }
            s.calm = input.calm
            s.shift = input.shift
            s.resize(w.toFloat() / h)
            if (applied !== input) {
                if (!began) {
                    s.begin(input.mode, input.live, input.picked, input.home)
                    began = true
                } else {
                    s.update(input.mode, input.live, input.picked, input.home)
                }
                applied = input
                dirty = true
            }

            // Capped at 50ms in the stage: a frame that comes late drops the time it missed.
            val dt = if (last == 0L) 0f else ((frameTimeNanos - last) / 1e9f).coerceIn(0f, 0.05f)
            last = frameTimeNanos
            val moving = s.advance(dt)

            // Moving: every refresh. Only the orbits and the dot: about 30 a second.
            // Calm: only when something changed.
            val due = moving || dirty || (!input.calm && frameTimeNanos - drawn >= IDLE_NANOS)
            if (due) {
                val dp = this@WorldView.resources.displayMetrics.density *
                    (w.toFloat() / maxOf(1, this@WorldView.width))
                painter.draw(s, w, h, dp, input.light, input.bg)
                drawn = frameTimeNanos
                dirty = false
                if (EGL14.eglSwapBuffers(display, surface)) {
                    if (!shown) {
                        shown = true
                        this@WorldView.post { this@WorldView.animate().alpha(1f).setDuration(550).start() }
                    }
                } else if (EGL14.eglGetError() == EGL14.EGL_CONTEXT_LOST) {
                    // The GPU was reset out from under us; build the context again. The scene lives on the CPU.
                    tearDown()
                    if (!setUp()) {
                        android.os.Looper.myLooper()?.quit()
                        return
                    }
                    dirty = true
                }
            }
            if (!input.calm || moving || dirty) schedule()
        }
    }

    companion object {
        /** Device pixels to the dp at most drawn at: about what the desktop's canvas is capped at. */
        private const val MAX_DENSITY = 1.75f

        /** A frame about every 33 ms, less a few so a 60 Hz display's every second refresh qualifies. */
        private const val IDLE_NANOS = 29_000_000L
    }
}

/**
 * The world as a layer to put behind the screen's content; fills what it is given.
 * [input] should be remembered by whoever makes it: the scene is told only when
 * it is a different one.
 */
@androidx.compose.runtime.Composable
fun WorldLayer(input: WorldInput, paused: Boolean, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { WorldView(it) },
        modifier = modifier,
        update = { v ->
            v.input = input
            v.paused = paused
        },
    )
}
