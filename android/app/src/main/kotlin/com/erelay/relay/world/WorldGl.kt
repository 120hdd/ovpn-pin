package com.erelay.relay.world

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import com.erelay.relay.ui.flagBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Paints a [Stage] with OpenGL ES 3: the planet, every country's border, the
 * outline of the one in focus, the orbits, the ripples, the arc and the dots.
 * GL thread only; it owns every GL object and holds none of the scene's state,
 * so a new context just builds this again and carries on from the Stage.
 *
 * Draw order is the desktop's: planet (writes depth), then everything that
 * glows on top of it, depth-tested against it so the far side of the borders,
 * orbits and arc is hidden, never writing depth so they do not fight each
 * other. On a dark page the glows add; on a light one they are ink, laid on
 * normally.
 */
internal class WorldGl(private val context: Context, private val world: WorldData) {

    private class Prog(val id: Int) {
        private val locs = HashMap<String, Int>()
        fun u(name: String) = locs.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    }

    /** A set of segments as instances of one quad: [count] of them. */
    private class Lines(val vao: Int, val vbo: Int, val count: Int)

    private class CountryTex(val mask: Int, val flag: Int)

    private lateinit var planet: Prog
    private lateinit var line: Prog
    private lateinit var sprite: Prog
    private lateinit var flat: Prog
    private lateinit var cone: Prog

    private var sphereVao = 0
    private var sphereCount = 0
    private var quadVbo = 0
    private var spriteVao = 0
    private var ringVao = 0
    private var ringCount = 0
    private var coneVao = 0
    private var blank = 0

    private lateinit var borders: Lines
    private lateinit var circle: Lines
    private var outlineO: Pair<String, Lines>? = null
    private var outlineD: Pair<String, Lines>? = null
    private var arcLines: Pair<Stage.Arc, Lines>? = null
    private val textures = LinkedHashMap<String, CountryTex>(8, 0.75f, true)

    fun setUp(): Boolean {
        planet = program(PLANET_VERT, PLANET_FRAG) ?: return false
        line = program(LINE_VERT, LINE_FRAG) ?: return false
        sprite = program(SPRITE_VERT, SPRITE_FRAG) ?: return false
        flat = program(FLAT_VERT, FLAT_FRAG) ?: return false
        cone = program(CONE_VERT, CONE_FRAG) ?: return false

        GLES30.glUseProgram(planet.id)
        GLES30.glUniform1i(planet.u("uMaskO"), 0)
        GLES30.glUniform1i(planet.u("uMaskD"), 1)
        GLES30.glUniform1i(planet.u("uFlagO"), 2)
        GLES30.glUniform1i(planet.u("uFlagD"), 3)

        buildSphere(96, 64)
        // The quad every line segment is an instance of: x = 0 at the segment's start, 1 at its end; y = the side.
        quadVbo = buffer(floatArrayOf(0f, -1f, 0f, 1f, 1f, -1f, 1f, 1f))
        spriteVao = vao { bind(buffer(floatArrayOf(-1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f)), 0, 2) }
        buildRing()
        val none = IntArray(1)
        GLES30.glGenVertexArrays(1, none, 0)
        coneVao = none[0]

        borders = lines(ringSegments(world.rings.indices.toList()))
        circle = lines(FloatArray(CIRCLE * 6).also {
            for (i in 0 until CIRCLE) {
                val a = 2 * PI.toFloat() * i / CIRCLE
                val b = 2 * PI.toFloat() * (i + 1) / CIRCLE
                it[i * 6] = cos(a); it[i * 6 + 2] = sin(a)
                it[i * 6 + 3] = cos(b); it[i * 6 + 5] = sin(b)
            }
        })
        blank = texture(null)
        return true
    }

    // -- one frame ----------------------------------------------------------------------

    /**
     * [w] x [h] pixels, [dp] of them to the dp, on a page of colour [bg]. [light] is a light theme.
     */
    fun draw(stage: Stage, w: Int, h: Int, dp: Float, light: Boolean, bg: FloatArray) {
        val (view, proj) = stage.camera()
        val vp = Mat.mul(proj, view)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(bg[0], bg[1], bg[2], 1f)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        if (!light) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glUseProgram(cone.id)
            GLES30.glUniform2f(cone.u("uRes"), w.toFloat(), h.toFloat())
            GLES30.glBindVertexArray(coneVao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        drawPlanet(stage, view, proj, light)

        // Everything that glows: tested against the planet's depth, writing none of its own.
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        if (light) GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        else GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        val tone = if (light) INK else NEON

        GLES30.glUseProgram(line.id)
        GLES30.glUniformMatrix4fv(line.u("uVP"), 1, false, vp, 0)
        GLES30.glUniform2f(line.u("uView"), w.toFloat(), h.toFloat())

        // Every border, faint.
        if (light) drawLines(borders, IDENTITY, 1.0012f, max(1.5f, 0.8f * dp), 0x6B7486, 0.7f)
        else drawLines(borders, IDENTITY, 1.0012f, max(1.5f, 0.8f * dp), tone.peri, 0.72f)

        // The orbits, once the camera is far enough out to see them.
        if (stage.far > 0.003f) {
            for (o in stage.orbits) {
                val m = Mat.rotationXYZ(o.tilt[0], o.spin, o.tilt[2])
                val c = tone.orbit[o.tone]
                drawLines(circle, m, o.radius, 8f * dp, c, 0.08f * stage.far)
                drawLines(circle, m, o.radius, 1.6f * dp, c, 0.6f * stage.far)
            }
        }

        // The ripples out of home while the servers are asked.
        for (r in stage.ripples) if (r.alpha > 0.003f) drawRipple(r, vp, tone.pulse)

        // The outline of the country in focus, and the one it left.
        stage.outlineOCode?.let { code ->
            val l = outlineOf(code, true)
            drawLines(l, IDENTITY, 1.0022f, 1.1f * dp, tone.peri, stage.outlineOAlpha)
        }
        stage.outlineDCode?.let { code ->
            if (stage.outlineDAlpha > 0.003f) {
                val l = outlineOf(code, false)
                drawLines(l, IDENTITY, 1.0022f, 1.1f * dp, tone.pulse, stage.outlineDAlpha)
            }
        }

        // The signal's arc.
        val arc = stage.arc
        if (arc != null) {
            val l = arcOf(arc)
            val n = (arc.segments * arc.drawn).toInt()
            if (n > 0) {
                val base = tone.pulse
                val c = floatArrayOf(
                    mixc(chan(base, 0), RED[0], arc.redness), mixc(chan(base, 1), RED[1], arc.redness),
                    mixc(chan(base, 2), RED[2], arc.redness))
                drawLinesRgb(l, IDENTITY, 1f, 5f * dp, c, 0.16f * arc.opacity, n)
                drawLinesRgb(l, IDENTITY, 1f, 1.5f * dp, c, arc.opacity, n)
            }
        }

        // The dots, last: all of them face the camera.
        GLES30.glUseProgram(sprite.id)
        GLES30.glUniformMatrix4fv(sprite.u("uV"), 1, false, view, 0)
        GLES30.glUniformMatrix4fv(sprite.u("uP"), 1, false, proj, 0)
        GLES30.glBindVertexArray(spriteVao)
        for (o in stage.orbits) {
            val m = Mat.rotationXYZ(o.tilt[0], o.spin, o.tilt[2])
            val c = tone.orbit[o.tone]
            for (i in 0 until o.riders) {
                val a = 2 * PI.toFloat() * o.u[i]
                dot(Mat.point(m, V3(cos(a) * o.radius, 0f, sin(a) * o.radius)), 0.09f, c, o.riderAlpha[i])
            }
        }
        if (arc != null) {
            if (arc.comet) dot(arc.at(arc.drawn), 0.12f, if (light) tone.pulse else 0xFFFFFF, arc.cometAlpha)
            for (i in arc.traffic.indices) {
                val a = arc.traffic[i]
                if (a > 0.003f) stage.trafficAt(i)?.let { dot(it, 0.05f, tone.pulse, a) }
            }
        }
        GLES30.glDepthMask(true)
    }

    private fun drawPlanet(stage: Stage, view: FloatArray, proj: FloatArray, light: Boolean) {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glUseProgram(planet.id)
        GLES30.glUniformMatrix4fv(planet.u("uV"), 1, false, view, 0)
        GLES30.glUniformMatrix4fv(planet.u("uP"), 1, false, proj, 0)
        GLES30.glUniform1f(planet.u("uLight"), if (light) 1f else 0f)
        GLES30.glUniform1f(planet.u("uRed"), stage.red)
        fill("O", 0, 2, stage.fillOCode, stage.fillO, stage.landO, stage)
        fill("D", 1, 3, stage.fillDCode, stage.fillD, stage.landD, stage)
        GLES30.glBindVertexArray(sphereVao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, sphereCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    private fun fill(k: String, maskUnit: Int, flagUnit: Int, code: String?, amount: Float, land: V3, stage: Stage) {
        val country = world[code]
        val tex = if (country != null) textureOf(code!!, country) else null
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + maskUnit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex?.mask ?: blank)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + flagUnit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex?.flag ?: blank)
        GLES30.glUniform1f(planet.u("uHas$k"), if (country != null) 1f else 0f)
        GLES30.glUniform1f(planet.u("uFill$k"), amount)
        if (country != null) {
            val b = country.box
            GLES30.glUniform4f(planet.u("uBox$k"), b[0], b[1], b[2], b[3])
            GLES30.glUniform1f(planet.u("uSpan$k"), if (k == "O") stage.spanO else stage.spanD)
            GLES30.glUniform3f(planet.u("uLand$k"), land.x, land.y, land.z)
        }
    }

    // -- drawing parts -------------------------------------------------------------------

    private fun drawLines(l: Lines, model: FloatArray, r: Float, widthPx: Float, hex: Int, alpha: Float) =
        drawLinesRgb(l, model, r, widthPx, floatArrayOf(chan(hex, 0), chan(hex, 1), chan(hex, 2)), alpha, l.count)

    private fun drawLinesRgb(l: Lines, model: FloatArray, r: Float, widthPx: Float, rgb: FloatArray, alpha: Float, count: Int) {
        GLES30.glUseProgram(line.id)
        GLES30.glUniformMatrix4fv(line.u("uModel"), 1, false, model, 0)
        GLES30.glUniform1f(line.u("uR"), r)
        GLES30.glUniform1f(line.u("uWidth"), widthPx)
        GLES30.glUniform4f(line.u("uColor"), rgb[0], rgb[1], rgb[2], alpha)
        GLES30.glBindVertexArray(l.vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
    }

    private fun dot(at: V3, size: Float, hex: Int, alpha: Float) {
        GLES30.glUniform3f(sprite.u("uC"), at.x, at.y, at.z)
        GLES30.glUniform1f(sprite.u("uSize"), size)
        GLES30.glUniform4f(sprite.u("uColor"), chan(hex, 0), chan(hex, 1), chan(hex, 2), alpha)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** A flat ring standing on the surface at the ripple's point, outward its normal, [r.scale] across. */
    private fun drawRipple(r: Stage.Ripple, vp: FloatArray, hex: Int) {
        val n = r.pos.normalized()
        var t1 = V3(0f, 1f, 0f) cross n
        if (t1.length < 1e-4f) t1 = V3(1f, 0f, 0f)
        t1 = t1.normalized()
        val t2 = n cross t1
        val s = r.scale
        val m = Mat.identity()
        m[0] = t1.x * s; m[1] = t1.y * s; m[2] = t1.z * s
        m[4] = t2.x * s; m[5] = t2.y * s; m[6] = t2.z * s
        m[8] = n.x * s; m[9] = n.y * s; m[10] = n.z * s
        m[12] = r.pos.x; m[13] = r.pos.y; m[14] = r.pos.z
        GLES30.glUseProgram(flat.id)
        GLES30.glUniformMatrix4fv(flat.u("uMVP"), 1, false, Mat.mul(vp, m), 0)
        GLES30.glUniform4f(flat.u("uColor"), chan(hex, 0), chan(hex, 1), chan(hex, 2), r.alpha)
        GLES30.glBindVertexArray(ringVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, ringCount)
    }

    // -- a country's textures ---------------------------------------------------------------

    /**
     * The mask (its rings filled white over its own box, 1024 across) and the
     * flag. Made on first use and kept for the last few countries; a flag the
     * app has no picture of is a quiet grey, so the fill still reads.
     */
    private fun textureOf(code: String, c: WorldData.Country): CountryTex {
        textures[code]?.let { return it }
        val b = c.box
        val cw = 1024
        val ch = max(64, min(1024, (1024f * (b[3] - b[1]) / max(0.01f, b[2] - b[0])).roundToInt()))
        val bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        for (i in c.rings) {
            val r = world.rings[i]
            val p = Path()
            for (k in 0 until r.size / 2) {
                val x = (r[k * 2] - b[0]) / (b[2] - b[0]) * cw
                val y = (b[3] - r[k * 2 + 1]) / (b[3] - b[1]) * ch
                if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            canvas.drawPath(p, paint)
        }
        val mask = texture(bmp)
        bmp.recycle()
        val flag = mapFlag(code)?.let { bmp -> texture(bmp, mipmaps = true).also { bmp.recycle() } }
            ?: texture(flagBitmap(context, code)?.asAndroidBitmap(), grey = true)
        val t = CountryTex(mask, flag)
        textures[code] = t
        // The last four only: two are in use, the rest are the ones just left.
        while (textures.size > 4) {
            val eldest = textures.entries.first()
            GLES30.glDeleteTextures(2, intArrayOf(eldest.value.mask, eldest.value.flag), 0)
            textures.remove(eldest.key)
        }
        return t
    }

    /**
     * The flag made for the map: 4:3 at 512 x 384 (assets/flags43, rendered from
     * the desktop's SVGs), where the list's round chips are small squares that
     * blur and crop when stretched over a country. Null if there is none.
     */
    private fun mapFlag(code: String): Bitmap? {
        for (name in listOf(code, if (code == "gb") "uk" else if (code == "uk") "gb" else null)) {
            if (name == null) continue
            try {
                context.assets.open("flags43/$name.webp").use { return BitmapFactory.decodeStream(it) }
            } catch (_: java.io.IOException) {
            }
        }
        return null
    }

    /**
     * A texture from [bmp]; with none, a single texel: black, or [grey] for a flag
     * there is no picture of. With [mipmaps] it stays crisp when seen from far off.
     */
    private fun texture(bmp: Bitmap?, grey: Boolean = false, mipmaps: Boolean = false): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE7)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        if (bmp != null) {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        } else {
            val v = if (grey) 0x66.toByte() else 0
            val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            px.put(v).put(v).put(v).put(0xFF.toByte()).position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
        }
        val mip = mipmaps && bmp != null
        if (mip) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
            if (mip) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    // -- geometry ------------------------------------------------------------------------------

    private fun outlineOf(code: String, origin: Boolean): Lines {
        val have = if (origin) outlineO else outlineD
        if (have != null && have.first == code) return have.second
        have?.let { release(it.second) }
        val idx = world[code]?.rings?.toList() ?: emptyList()
        val made = code to lines(ringSegments(idx))
        if (origin) outlineO = made else outlineD = made
        return made.second
    }

    private fun arcOf(arc: Stage.Arc): Lines {
        arcLines?.let { if (it.first === arc) return it.second else release(it.second) }
        val p = arc.points
        val seg = FloatArray(arc.segments * 6)
        for (i in 0 until arc.segments) System.arraycopy(p, i * 3, seg, i * 6, 6)
        val l = lines(seg)
        arcLines = arc to l
        return l
    }

    /** Each ring's segments, as pairs of points on the unit sphere. */
    private fun ringSegments(indices: List<Int>): FloatArray {
        var n = 0
        for (i in indices) n += world.rings[i].size / 2
        val out = FloatArray(n * 6)
        var o = 0
        for (i in indices) {
            val r = world.rings[i]
            val m = r.size / 2
            for (k in 0 until m) {
                val a = dirOf(r[k * 2], r[k * 2 + 1])
                val k2 = (k + 1) % m
                val b = dirOf(r[k2 * 2], r[k2 * 2 + 1])
                out[o++] = a.x; out[o++] = a.y; out[o++] = a.z
                out[o++] = b.x; out[o++] = b.y; out[o++] = b.z
            }
        }
        return out
    }

    private fun lines(segments: FloatArray): Lines {
        val inst = buffer(segments)
        val v = vao {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst)
            for (a in 1..2) {
                GLES30.glEnableVertexAttribArray(a)
                GLES30.glVertexAttribPointer(a, 3, GLES30.GL_FLOAT, false, 24, (a - 1) * 12)
                GLES30.glVertexAttribDivisor(a, 1)
            }
        }
        return Lines(v, inst, segments.size / 6)
    }

    private fun release(l: Lines) {
        GLES30.glDeleteVertexArrays(1, intArrayOf(l.vao), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(l.vbo), 0)
    }

    /** A UV sphere, positions only (the planet's shader works out longitude and latitude from them). */
    private fun buildSphere(segs: Int, rings: Int) {
        val verts = FloatArray((rings + 1) * (segs + 1) * 3)
        var n = 0
        for (i in 0..rings) {
            val lat = (0.5f - i.toFloat() / rings) * PI.toFloat()
            for (j in 0..segs) {
                val lon = (j.toFloat() / segs - 0.5f) * 2f * PI.toFloat()
                verts[n++] = cos(lat) * sin(lon)
                verts[n++] = sin(lat)
                verts[n++] = cos(lat) * cos(lon)
            }
        }
        val idx = ShortArray(rings * segs * 6)
        var m = 0
        for (i in 0 until rings) for (j in 0 until segs) {
            val a = i * (segs + 1) + j
            val b = a + segs + 1
            // Counter-clockwise seen from outside, so the back faces can be culled.
            idx[m++] = a.toShort(); idx[m++] = b.toShort(); idx[m++] = (a + 1).toShort()
            idx[m++] = (a + 1).toShort(); idx[m++] = b.toShort(); idx[m++] = (b + 1).toShort()
        }
        sphereCount = idx.size
        sphereVao = vao {
            bind(buffer(verts), 0, 3)
            val ib = IntArray(1)
            GLES30.glGenBuffers(1, ib, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib[0])
            val bb = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder())
            bb.asShortBuffer().put(idx).position(0)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, bb, GLES30.GL_STATIC_DRAW)
        }
    }

    /** The ripple's ring as a strip: 0.96 to 1 in radius, in the xy plane. */
    private fun buildRing() {
        val n = 64
        val v = FloatArray((n + 1) * 2 * 3)
        for (i in 0..n) {
            val a = 2 * PI.toFloat() * i / n
            v[i * 6] = cos(a) * 0.96f; v[i * 6 + 1] = sin(a) * 0.96f
            v[i * 6 + 3] = cos(a); v[i * 6 + 4] = sin(a)
        }
        ringCount = (n + 1) * 2
        ringVao = vao { bind(buffer(v), 0, 3) }
    }

    private fun vao(setUp: () -> Unit): Int {
        val a = IntArray(1)
        GLES30.glGenVertexArrays(1, a, 0)
        GLES30.glBindVertexArray(a[0])
        setUp()
        GLES30.glBindVertexArray(0)
        return a[0]
    }

    private fun bind(vbo: Int, attrib: Int, size: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(attrib)
        GLES30.glVertexAttribPointer(attrib, size, GLES30.GL_FLOAT, false, 0, 0)
    }

    private fun buffer(data: FloatArray): Int {
        val b = IntArray(1)
        GLES30.glGenBuffers(1, b, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b[0])
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(data).position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, bb, GLES30.GL_STATIC_DRAW)
        return b[0]
    }

    private fun program(vs: String, fs: String): Prog? {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs) ?: return null
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs) ?: return null
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.w("Relay", "world link: ${GLES30.glGetProgramInfoLog(p)}")
            return null
        }
        return Prog(p)
    }

    private fun compile(type: Int, src: String): Int? {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.w("Relay", "world shader: ${GLES30.glGetShaderInfoLog(s)}")
            return null
        }
        return s
    }

    // -- colours -------------------------------------------------------------------------------

    /** The neon palette on a dark page and the same hues deepened to ink on a light one. */
    private class Tone(val peri: Int, val pulse: Int, val orbit: IntArray)

    companion object {
        private const val CIRCLE = 180
        private val IDENTITY = Mat.identity()
        private val RED = floatArrayOf(1f, 0.42f, 0.48f)
        private val NEON = Tone(0x8AA6FF, 0x1ED760, intArrayOf(0x8AA6FF, 0x62E6F2, 0xCF9BFF))
        private val INK = Tone(0x4C63B8, 0x139A48, intArrayOf(0x4C63B8, 0x1E8C98, 0x8A54C4))

        private fun chan(hex: Int, channel: Int) = ((hex shr (16 - 8 * channel)) and 0xFF) / 255f
        private fun mixc(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
