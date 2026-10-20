package com.erelay.relay.orb

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.provider.Settings
import android.util.Log
import android.view.TextureView
import com.erelay.relay.data.Mode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The connection core: the desktop's twisted sphere, drawn with OpenGL ES 3.
 *
 * A TextureView rather than a GLSurfaceView. A SurfaceView is a hole punched
 * through the window, so the sheets that slide up over the main screen would
 * either be drawn under it or need the orb hidden for them; a TextureView is
 * an ordinary layer and composites like everything else, transparency
 * included.
 *
 * It draws on a thread of its own, at the display's pace, only while it is
 * shown and not [paused]. The state it shows is set from the main thread and
 * eased toward on the render thread, the way sphere.js eases toward the
 * hero's data-state.
 */
class OrbView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile var mode: Mode = Mode.OFF

    /** The three --blob colours of the current state, as 0..1 rgb triples. */
    @Volatile var colors: FloatArray = floatArrayOf(
        0.61f, 0.55f, 1.0f, 0.30f, 0.89f, 0.82f, 0.28f, 0.23f, 0.76f)

    /** Covered by a sheet or the app in the background: stop drawing. */
    var paused: Boolean = false
        set(value) {
            field = value
            render?.wake()
        }

    private var render: RenderThread? = null

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        render = RenderThread(surface, width, height).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        render?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        render?.quit()
        render = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun reducedMotion(): Boolean = try {
        Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) {
        false
    }

    // -- the render thread -------------------------------------------------------

    private inner class RenderThread(
        private val texture: SurfaceTexture,
        @Volatile private var width: Int,
        @Volatile private var height: Int,
    ) : Thread("orb") {

        private val lock = ReentrantLock()
        private val nudge = lock.newCondition()
        @Volatile private var running = true
        @Volatile private var sized = true

        fun wake() = lock.withLock { nudge.signalAll() }

        fun resize(w: Int, h: Int) {
            width = w
            height = h
            sized = true
            wake()
        }

        fun quit() {
            running = false
            wake()
            try { join(500) } catch (e: InterruptedException) { }
        }

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        override fun run() {
            try {
                if (!setUp()) return
                loop()
            } catch (e: Throwable) {
                Log.w("Relay", "orb: ${e.message}")
            } finally {
                tearDown()
            }
        }

        /**
         * MSAA where the GPU offers it, which is what gives the silhouette its
         * soft edge: the resolve writes partial coverage into alpha and the
         * view composites with it. Without it the edge is merely hard.
         */
        private fun setUp(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

            fun choose(samples: Int): EGLConfig? {
                val attrs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 16,
                    EGL14.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                    EGL14.EGL_SAMPLES, samples,
                    EGL14.EGL_NONE)
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0)
                return if (count[0] > 0) configs[0] else null
            }
            val config = choose(4) ?: choose(0) ?: return false

            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) return false
            surface = EGL14.eglCreateWindowSurface(display, config, texture,
                intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) return false
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false
            return sphere.setUp()
        }

        private fun tearDown() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }

        private val sphere = Sphere()

        private fun loop() {
            var last = 0L
            var settled = false
            while (running) {
                if (paused) {
                    last = 0L
                    lock.withLock { if (paused && running) nudge.await(1000, TimeUnit.MILLISECONDS) }
                    continue
                }
                if (sized) {
                    sized = false
                    sphere.viewport(width, height)
                    settled = false
                }
                val now = System.nanoTime()
                val dt = if (last == 0L) 0f else min((now - last) / 1e9f, 0.1f)
                last = now

                val moving = sphere.ease(dt, mode, colors)
                if (reducedMotion()) {
                    // One still frame, picked for its profile, redrawn only
                    // while the colours are still easing.
                    if (settled && !moving) {
                        lock.withLock { nudge.await(250, TimeUnit.MILLISECONDS) }
                        continue
                    }
                    settled = !moving
                    sphere.draw(3.1f, 0.45f)
                } else {
                    sphere.advance(dt)
                    sphere.draw()
                }
                // Blocks until the display takes the frame: this is the pace.
                if (!EGL14.eglSwapBuffers(display, surface)) {
                    lock.withLock { nudge.await(16, TimeUnit.MILLISECONDS) }
                }
            }
        }
    }
}

/** sphere.js's STATES: how fast, how wrung, how lumpy, per look. */
private class Pace(val spin: Float, val wave: Float, val twist: Float, val noise: Float, val density: Float)

private fun paceOf(mode: Mode) = when (mode) {
    Mode.OFF -> Pace(0.17f, 0.32f, 0.92f, 0.24f, 2.50f)
    Mode.BUSY -> Pace(0.52f, 1.00f, 1.05f, 0.28f, 2.60f)
    Mode.ON -> Pace(0.24f, 0.42f, 0.62f, 0.20f, 2.45f)
    Mode.FAIL -> Pace(0.05f, 0.10f, 0.38f, 0.19f, 2.40f)
}

/** The mesh, the program and the eased state. GL thread only. */
private class Sphere {
    private val fov = 0.50f
    private val dist = 1.68f / sin(fov / 2)
    private val tilt = -0.24f

    private var program = 0
    private var indexCount = 0
    private val u = HashMap<String, Int>()

    // Eased values. Start at rest; the first frames ease into whatever state
    // the window is actually in.
    private var spinRate = 0.17f
    private var wave = 0.32f
    private var twist = 0.92f
    private var noise = 0.24f
    private var density = 2.50f
    private val col = floatArrayOf(0.61f, 0.55f, 1.0f, 0.30f, 0.89f, 0.82f, 0.28f, 0.23f, 0.76f)
    private var started = false

    private var phase = 0f
    private var spin = 0f

    fun setUp(): Boolean {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERT) ?: return false
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAG) ?: return false
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.w("Relay", "orb link: ${GLES30.glGetProgramInfoLog(program)}")
            return false
        }
        GLES30.glUseProgram(program)
        for (n in listOf("u_proj", "u_rot", "u_dist", "u_time", "u_density", "u_strength",
            "u_twist", "u_freq", "u_pa", "u_pb", "u_pc", "u_pd", "u_rim")) {
            u[n] = GLES30.glGetUniformLocation(program, n)
        }

        val (pos, idx) = icosphere(4)
        indexCount = idx.size
        val vao = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])
        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        val vb = ByteBuffer.allocateDirect(pos.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(pos).also { it.position(0) }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, pos.size * 4, vb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        val ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder())
            .asShortBuffer().put(idx).also { it.position(0) }
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ib, GLES30.GL_STATIC_DRAW)

        // The twist folds the body through itself and the inside faces are
        // part of the shape: depth sorts them, nothing is culled, and nothing
        // blends - every fragment is opaque, the soft edge is MSAA's.
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glUniform1f(u["u_dist"]!!, dist)
        GLES30.glUniform1f(u["u_freq"]!!, 2.30f)
        return true
    }

    fun viewport(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUniformMatrix4fv(u["u_proj"]!!, 1, false,
            perspective(fov, w.toFloat() / h, 0.1f, 20f), 0)
    }

    /** One step of 1 - e^(-5dt) toward the target. True while anything moved. */
    fun ease(dt: Float, mode: Mode, target: FloatArray): Boolean {
        val p = paceOf(mode)
        if (!started) {
            started = true
            target.copyInto(col)
        }
        val k = 1 - exp(-dt * 5)
        var moving = false
        fun step(cur: Float, want: Float): Float {
            val d = want - cur
            if (abs(d) <= 1e-4f) return cur
            moving = true
            return cur + d * k
        }
        spinRate = step(spinRate, p.spin)
        wave = step(wave, p.wave)
        twist = step(twist, p.twist)
        noise = step(noise, p.noise)
        density = step(density, p.density)
        for (i in 0 until 9) col[i] = step(col[i], target[i])
        return moving
    }

    fun advance(dt: Float) {
        phase += dt * wave
        spin = (spin + dt * spinRate) % (2 * PI.toFloat())
    }

    fun draw(atPhase: Float = phase, atSpin: Float = spin) {
        val cs = cos(atSpin)
        val ss = sin(atSpin)
        val cx = cos(tilt)
        val sx = sin(tilt)
        GLES30.glUniformMatrix3fv(u["u_rot"]!!, 1, false, floatArrayOf(
            cs, sx * ss, -cx * ss,
            0f, cx, sx,
            ss, -sx * cs, cx * cs), 0)

        // fitPalette: a cosine gradient solved to pass exactly through
        // --blob-a at t=0, -b at 1/2 and -c at 1. See sphere.js.
        val pa = FloatArray(3)
        val pb = FloatArray(3)
        val pd = FloatArray(3)
        for (i in 0 until 3) {
            val a = col[i]
            val b = col[3 + i]
            val c = col[6 + i]
            val mid = (a + c) / 2
            val x = (a - c) / 2
            val y = mid - b
            pa[i] = mid
            pb[i] = hypot(x, y)
            pd[i] = (atan2(y, x) / (2 * PI)).toFloat()
        }
        GLES30.glUniform3fv(u["u_pa"]!!, 1, pa, 0)
        GLES30.glUniform3fv(u["u_pb"]!!, 1, pb, 0)
        GLES30.glUniform3f(u["u_pc"]!!, 0.5f, 0.5f, 0.5f)
        GLES30.glUniform3fv(u["u_pd"]!!, 1, pd, 0)
        GLES30.glUniform3f(u["u_rim"]!!, col[0], col[1], col[2])
        GLES30.glUniform1f(u["u_time"]!!, atPhase)
        GLES30.glUniform1f(u["u_twist"]!!, twist)
        GLES30.glUniform1f(u["u_strength"]!!, noise)
        GLES30.glUniform1f(u["u_density"]!!, density)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
    }

    private fun compile(type: Int, src: String): Int? {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.w("Relay", "orb shader: ${GLES30.glGetShaderInfoLog(s)}")
            return null
        }
        return s
    }
}

private fun perspective(fovy: Float, aspect: Float, near: Float, far: Float): FloatArray {
    val f = 1 / tan(fovy / 2)
    val nf = 1 / (near - far)
    return floatArrayOf(
        f / aspect, 0f, 0f, 0f,
        0f, f, 0f, 0f,
        0f, 0f, (far + near) * nf, -1f,
        0f, 0f, 2 * far * near * nf, 0f)
}

/**
 * A subdivided icosahedron: even triangles everywhere, no pinched pole for
 * the twist to shred. Order 4 is 2562 vertices, 5120 triangles. One midpoint
 * cache across every level, so an edge is only ever split once.
 */
private fun icosphere(order: Int): Pair<FloatArray, ShortArray> {
    val t = ((1 + sqrt(5.0)) / 2).toFloat()
    val pos = mutableListOf(
        -1f, t, 0f, 1f, t, 0f, -1f, -t, 0f, 1f, -t, 0f,
        0f, -1f, t, 0f, 1f, t, 0f, -1f, -t, 0f, 1f, -t,
        t, 0f, -1f, t, 0f, 1f, -t, 0f, -1f, -t, 0f, 1f)
    for (i in pos.indices step 3) {
        val l = sqrt(pos[i] * pos[i] + pos[i + 1] * pos[i + 1] + pos[i + 2] * pos[i + 2])
        pos[i] /= l; pos[i + 1] /= l; pos[i + 2] /= l
    }
    var idx = mutableListOf(
        0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11,
        1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
        3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9,
        4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1)
    val seen = HashMap<Int, Int>()
    fun mid(a: Int, b: Int): Int {
        val key = if (a < b) a * 65536 + b else b * 65536 + a
        seen[key]?.let { return it }
        val x = (pos[a * 3] + pos[b * 3]) / 2
        val y = (pos[a * 3 + 1] + pos[b * 3 + 1]) / 2
        val z = (pos[a * 3 + 2] + pos[b * 3 + 2]) / 2
        val l = sqrt(x * x + y * y + z * z)
        val n = pos.size / 3
        pos += listOf(x / l, y / l, z / l)
        seen[key] = n
        return n
    }
    repeat(order) {
        val next = ArrayList<Int>(idx.size * 4)
        for (i in idx.indices step 3) {
            val a = idx[i]
            val b = idx[i + 1]
            val c = idx[i + 2]
            val ab = mid(a, b)
            val bc = mid(b, c)
            val ca = mid(c, a)
            next += listOf(a, ab, ca, b, bc, ab, c, ca, bc, ab, bc, ca)
        }
        idx = next
    }
    return pos.toFloatArray() to ShortArray(idx.size) { idx[it].toShort() }
}
