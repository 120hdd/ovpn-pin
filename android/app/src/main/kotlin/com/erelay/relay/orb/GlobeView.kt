package com.erelay.relay.orb

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.provider.Settings
import android.util.Log
import android.view.TextureView
import com.erelay.relay.data.Mode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The hologram globe, drawn with OpenGL ES 3 (see Shaders.kt for the look).
 *
 * A TextureView rather than a GLSurfaceView. A SurfaceView is a hole punched
 * through the window, so the sheets that slide up over the main screen would
 * either be drawn under it or need the globe hidden for them; a TextureView is
 * an ordinary layer and composites like everything else, transparency
 * included.
 *
 * It draws on a thread of its own, at the display's pace, only while it is
 * shown and not [paused]. The state it shows is set from the main thread and
 * eased toward on the render thread.
 *
 * Where the globe sits is [place]: its centre as fractions of the view (x from
 * the left, y from the top) and its radius as a fraction of the view's height.
 * A centre on the view's edge is how the Home screen shows half of it.
 */
class GlobeView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile var mode: Mode = Mode.OFF

    /** The line colour the state asks for, 0..1 rgb; the render thread eases toward it. */
    @Volatile var color: FloatArray = CYAN
        set(value) {
            val same = field.contentEquals(value)
            field = value
            if (!same) render?.wake()
        }

    /** On a light page the lines are ink, not light. */
    @Volatile var light: Boolean = false
        set(value) {
            val same = field == value
            field = value
            if (!same) render?.wake()
        }

    /** Asked not to move: one still frame, the reduced-motion one. */
    @Volatile var still: Boolean = false
        set(value) {
            val same = field == value
            field = value
            if (!same) render?.wake()
        }

    /**
     * Where the tunnel comes out, as [latitude, longitude] in radians; null
     * when there is nowhere to point at. The globe turns to face it and wears
     * one marker there.
     */
    @Volatile var focus: FloatArray? = null
        set(value) {
            val old = field
            val same = if (old == null || value == null) old == null && value == null
                       else old.contentEquals(value)
            field = value
            if (!same) render?.wake()
        }

    /** Centre x, centre y (fractions of the view, from the top left) and radius (fraction of its height). */
    @Volatile var place: FloatArray = floatArrayOf(0.5f, 0.5f, 0.46f)
        set(value) {
            val same = field.contentEquals(value)
            field = value
            if (!same) render?.wake()
        }

    /**
     * Covered by a sheet or the app in the background: stop drawing.
     * Volatile like the others: set on the main thread, read on the render one.
     */
    @Volatile var paused: Boolean = false
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
        // Never two threads drawing into one view.
        render?.quit()
        // Hidden until the first frame is actually in the texture, then faded
        // up. Setting up EGL and compiling the shader takes a few frames, and
        // the globe used to pop into an empty stage after them, every time the
        // surface was made again.
        animate().cancel()
        alpha = 0f
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

    /**
     * Draws once per display frame, on the display's own clock.
     *
     * A TextureView does not pace whoever draws into it: eglSwapBuffers on
     * one returns at once, so a plain loop renders as fast as the GPU allows
     * and times each frame by the wall clock. Frames then land unevenly
     * against the screen's refresh and the motion judders. Choreographer hands
     * this thread one callback per vsync with that vsync's timestamp, so each
     * frame is one refresh apart and moves by exactly one refresh's worth.
     */
    private inner class RenderThread(
        private val texture: SurfaceTexture,
        @Volatile private var width: Int,
        @Volatile private var height: Int,
    ) : Thread("globe"), android.view.Choreographer.FrameCallback {

        @Volatile private var running = true
        @Volatile private var sized = true
        @Volatile private var handler: android.os.Handler? = null
        private var choreographer: android.view.Choreographer? = null
        private var scheduled = false
        private var last = 0L
        private var settled = false
        private var reduced = false
        private var shown = false

        /** Start drawing again after a pause, or after the look changed while still. */
        fun wake() {
            handler?.post {
                settled = false
                schedule()
            }
        }

        fun resize(w: Int, h: Int) {
            width = w
            height = h
            sized = true
            wake()
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

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        override fun run() {
            android.os.Looper.prepare()
            try {
                if (!setUp()) return
                handler = android.os.Handler(android.os.Looper.myLooper()!!)
                // A quit() that came before there was a handler had nothing
                // to post its looper-quit to; without this the thread went
                // on into loop() for good, holding its EGL context.
                if (!running) return
                choreographer = android.view.Choreographer.getInstance()
                // Read once: it is a cross-process lookup.
                reduced = reducedMotion()
                schedule()
                android.os.Looper.loop()
            } catch (e: Throwable) {
                // No GL, or a driver that refuses: the view stays transparent
                // and the screen is just the screen.
                Log.w("Relay", "globe: ${e.message}")
            } finally {
                tearDown()
            }
        }

        /** No depth buffer and no MSAA: the shader draws its own soft edges. */
        private fun setUp(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0)
            val config = (if (count[0] > 0) configs[0] else null) ?: return false

            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false
            surface = EGL14.eglCreateWindowSurface(display, config, texture,
                intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) return false
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) return false
            return globe.setUp(this@GlobeView.context)
        }

        private fun tearDown() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
            // Cleared, not left dangling: after a lost context this is set up
            // again, and a setUp() that fails halfway must not hand the final
            // tearDown() handles that were already destroyed.
            surface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            display = EGL14.EGL_NO_DISPLAY
        }

        private val globe = Globe()

        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            if (!running) return
            if (paused) {
                // Nothing is posted until wake(); the first frame back starts
                // its clock afresh rather than leaping by the time away.
                last = 0L
                return
            }
            if (sized) {
                sized = false
                GLES30.glViewport(0, 0, width, height)
                settled = false
            }
            // Capped at 50ms: a frame that comes late (a GC, the GPU busy
            // with a sheet's blur) drops the time it missed rather than
            // paying it back in one lurch.
            val dt = if (last == 0L) 0f
                     else ((frameTimeNanos - last) / 1e9f).coerceIn(0f, 0.05f)
            last = frameTimeNanos

            val still = still || reduced
            val moving = globe.ease(dt, mode, color, focus, still)
            if (still) {
                // One still frame, redrawn only while the colours are still
                // easing. Then nothing is scheduled until wake() says the
                // look changed.
                if (settled && !moving) return
                settled = !moving
            } else {
                globe.advance(dt)
            }
            globe.draw(width, height, place, light, still)

            if (EGL14.eglSwapBuffers(display, surface)) {
                if (!shown) {
                    shown = true
                    this@GlobeView.post { this@GlobeView.animate().alpha(1f).setDuration(550).start() }
                }
            } else if (EGL14.eglGetError() == EGL14.EGL_CONTEXT_LOST) {
                // The GPU was reset or power-managed out from under us. Every
                // GL call from here on would be a silent no-op and the globe
                // would sit frozen on its last frame for good, so build the
                // context again. Pose and colours live on the CPU and carry on.
                tearDown()
                if (!setUp()) {
                    android.os.Looper.myLooper()?.quit()
                    return
                }
                sized = true
            }
            schedule()
        }
    }

    companion object {
        /** The hologram's own cyan, #19D3CB. */
        val CYAN = floatArrayOf(0.10f, 0.83f, 0.80f)
    }
}

// -- pose --------------------------------------------------------------------------------

private const val TAU = (2 * PI).toFloat()

/** Radians per second, by state: working turns faster, a failure all but stops. */
private fun rateOf(mode: Mode) = when (mode) {
    Mode.OFF -> 0.36f
    Mode.BUSY -> 0.62f
    Mode.ON -> 0.30f
    Mode.FAIL -> 0.10f
}

/** Into (-PI, PI]. */
private fun wrap(a: Float): Float {
    var x = a % TAU
    if (x > PI) x -= TAU else if (x <= -PI) x += TAU
    return x
}

/** North tipped a little toward the viewer, and a lean: the source's rig tilt. */
private const val IDLE_PITCH = 0.22f
private const val ROLL = 0.12f

/** How far left of the globe's middle the connected country sits: 35 degrees of longitude. */
private const val FACE_OFFSET = 0.62f

/** The still frame: Africa, Arabia and India on the visible half. */
private const val STILL_YAW = -1.57f

/** The mesh, the program and the eased state. GL thread only. */
private class Globe {
    private var program = 0
    private var indexCount = 0
    private var maskTex = 0
    private val u = HashMap<String, Int>()

    // Eased values. The first frame snaps them to the state the view is
    // actually in, rather than easing in from rest: this object is made new
    // with every surface, so a connected globe used to swing from the idle
    // pose to its country each time the screen came back.
    private val col = CYAN3.copyOf()
    private var rate = rateOf(Mode.OFF)
    private var yaw = STILL_YAW
    private var pitch = IDLE_PITCH
    private var lock = 0f
    private var started = false
    private var markLat = 0f
    private var markLon = 0f
    private var time = 0f

    private val rot = FloatArray(9)
    private val mark = FloatArray(3)

    fun setUp(context: android.content.Context): Boolean {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERT) ?: return false
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAG) ?: return false
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.w("Relay", "globe link: ${GLES30.glGetProgramInfoLog(program)}")
            return false
        }
        GLES30.glUseProgram(program)
        for (n in listOf("u_rot", "u_center", "u_scale", "u_mask", "u_col", "u_mark", "u_res",
            "u_markAmt", "u_pulse", "u_light", "u_aa")) {
            u[n] = GLES30.glGetUniformLocation(program, n)
        }

        val (verts, idx) = sphereMesh(64, 48)
        indexCount = idx.size
        val vao = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])
        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(verts).also { it.position(0) }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        val ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder())
            .asShortBuffer().put(idx).also { it.position(0) }
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ib, GLES30.GL_STATIC_DRAW)

        loadMask(context)

        // Nothing is culled or depth-tested: both faces are drawn and add up,
        // the far side of the graticule showing through the near one. The
        // blend is premultiplied "over", the way a TextureView composites.
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glUniform1i(u["u_mask"]!!, 0)
        return true
    }

    /** assets/globe.png into texture unit 0; a black texel if it cannot be read, so the grid still draws. */
    private fun loadMask(context: android.content.Context) {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        maskTex = t[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        val bitmap = try {
            context.assets.open("globe.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.w("Relay", "globe mask: ${e.message}")
            null
        }
        if (bitmap != null) {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR_MIPMAP_LINEAR)
        } else {
            val black = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, black)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /**
     * One step of 1 - e^(-5dt) toward the target (about 600ms to settle): the
     * colour, the spin rate, and the lock on the connected country. True while
     * anything moved. Still, the pose snaps instead of turning.
     */
    fun ease(dt: Float, mode: Mode, target: FloatArray, focus: FloatArray?, still: Boolean): Boolean {
        if (focus != null) { markLat = focus[0]; markLon = focus[1] }
        val wantLock = if (focus != null && mode == Mode.ON) 1f else 0f
        if (!started) {
            started = true
            target.copyInto(col)
            rate = rateOf(mode)
            lock = wantLock
            if (lock > 0f) { yaw = -markLon - FACE_OFFSET; pitch = pitchFor(markLat) }
        }
        val k = 1 - exp(-dt * 5)
        var moving = false
        fun step(cur: Float, want: Float, by: Float = k): Float {
            val d = want - cur
            if (abs(d) <= 1e-4f) return cur
            moving = true
            return cur + d * by
        }
        for (i in 0 until 3) col[i] = step(col[i], target[i])
        if (still) {
            // A frame, not a motion: the pose is wherever it belongs.
            lock = wantLock
            rate = rateOf(mode)
            if (lock > 0f) { yaw = -markLon - FACE_OFFSET; pitch = pitchFor(markLat) }
            else { yaw = STILL_YAW; pitch = IDLE_PITCH }
        } else {
            val slow = 1 - exp(-dt * 2.4f)
            rate = step(rate, rateOf(mode), slow)
            lock = step(lock, wantLock, slow)
            pitch = step(pitch, if (lock > 0.01f) pitchFor(markLat) else IDLE_PITCH, slow)
            // Keep the loop alive while the globe is still free-running or settling.
            moving = true
        }
        return moving
    }

    fun advance(dt: Float) {
        time = (time + dt) % 2f
        // Turning freely, less and less as the lock takes hold; then drawn
        // into the pose that puts the connected country on the visible half.
        yaw += rate * (1 - lock) * dt
        if (lock > 0f) {
            val want = -markLon - FACE_OFFSET
            yaw += wrap(want - yaw) * (1 - exp(-dt * 2.6f)) * lock
        }
        yaw = wrap(yaw)
    }

    private fun pitchFor(lat: Float) = (0.10f + 0.6f * lat).coerceIn(-0.8f, 0.8f)

    fun draw(w: Int, h: Int, place: FloatArray, light: Boolean, still: Boolean) {
        if (w <= 0 || h <= 0) return
        // M = Rz(roll) * Rx(pitch) * Ry(yaw), one basis vector at a time.
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(ROLL); val sr = sin(ROLL)
        for (c in 0 until 3) {
            val x = if (c == 0) 1f else 0f
            val y = if (c == 1) 1f else 0f
            val z = if (c == 2) 1f else 0f
            val x1 = x * cy + z * sy
            val z1 = -x * sy + z * cy
            val y2 = y * cp - z1 * sp
            val z2 = y * sp + z1 * cp
            rot[c * 3] = x1 * cr - y2 * sr
            rot[c * 3 + 1] = x1 * sr + y2 * cr
            rot[c * 3 + 2] = z2
        }
        GLES30.glUniformMatrix3fv(u["u_rot"]!!, 1, false, rot, 0)

        val radius = place[2] * h
        GLES30.glUniform2f(u["u_center"]!!, place[0] * 2f - 1f, 1f - place[1] * 2f)
        GLES30.glUniform2f(u["u_scale"]!!, radius / (w / 2f), radius / (h / 2f))
        GLES30.glUniform2f(u["u_res"]!!, w.toFloat(), h.toFloat())
        GLES30.glUniform1f(u["u_aa"]!!, sqrt(3f / radius.coerceAtLeast(8f)))
        GLES30.glUniform3f(u["u_col"]!!, col[0], col[1], col[2])
        GLES30.glUniform1f(u["u_light"]!!, if (light) 1f else 0f)

        val cl = cos(markLat)
        mark[0] = cl * sin(markLon); mark[1] = sin(markLat); mark[2] = cl * cos(markLon)
        GLES30.glUniform3fv(u["u_mark"]!!, 1, mark, 0)
        GLES30.glUniform1f(u["u_markAmt"]!!, ((lock - 0.6f) / 0.4f).coerceIn(0f, 1f))
        GLES30.glUniform1f(u["u_pulse"]!!, if (still) 0.45f else time / 2f)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
    }

    private fun compile(type: Int, src: String): Int? {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.w("Relay", "globe shader: ${GLES30.glGetShaderInfoLog(s)}")
            return null
        }
        return s
    }
}

private val CYAN3 = GlobeView.CYAN

/**
 * A UV sphere: [rings] bands of latitude by [segs] of longitude, 3185 vertices
 * and 6144 triangles at 64 by 48. Each vertex is x, y, z (unit) and u, v with
 * u = 0.5 at longitude 0 and v = 0 at the north pole, so a vertex's uv is also
 * the texture coordinate of the equirectangular land mask. The seam column is
 * duplicated, which is what keeps uv continuous across it.
 */
private fun sphereMesh(segs: Int, rings: Int): Pair<FloatArray, ShortArray> {
    val verts = FloatArray((rings + 1) * (segs + 1) * 5)
    var n = 0
    for (i in 0..rings) {
        val v = i.toFloat() / rings
        val lat = (0.5f - v) * PI.toFloat()
        for (j in 0..segs) {
            val uu = j.toFloat() / segs
            val lon = (uu - 0.5f) * TAU
            verts[n++] = cos(lat) * sin(lon)
            verts[n++] = sin(lat)
            verts[n++] = cos(lat) * cos(lon)
            verts[n++] = uu
            verts[n++] = v
        }
    }
    val idx = ShortArray(rings * segs * 6)
    var m = 0
    for (i in 0 until rings) {
        for (j in 0 until segs) {
            val a = i * (segs + 1) + j
            val b = a + segs + 1
            idx[m++] = a.toShort(); idx[m++] = b.toShort(); idx[m++] = (a + 1).toShort()
            idx[m++] = (a + 1).toShort(); idx[m++] = b.toShort(); idx[m++] = (b + 1).toShort()
        }
    }
    return verts to idx
}
