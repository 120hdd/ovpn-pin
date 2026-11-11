package com.erelay.relay.world

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * The scene's arithmetic, with nothing of Android in it so a plain JVM test
 * can hold it to the numbers the desktop's atlas.js gives. The planet is a
 * sphere of radius 1 at the origin; y is north, z points at (lon 0, lat 0)
 * and x at (lon 90, lat 0).
 */

const val D2R = (PI / 180.0).toFloat()

/** The camera's vertical field of view, degrees. */
const val FOV = 36f

/** An immutable vector; the scene makes a handful per frame and that is nothing. */
class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(f: Float) = V3(x * f, y * f, z * f)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length get() = sqrt(x * x + y * y + z * z)
    fun normalized(): V3 {
        val l = length
        return if (l < 1e-9f) V3(0f, 0f, 1f) else V3(x / l, y / l, z / l)
    }
    /** The angle between two unit vectors, radians. */
    fun angleTo(o: V3): Float = acos((this dot o).coerceIn(-1f, 1f))
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
}

/** The point on the unit sphere above a longitude and latitude, in degrees. */
fun dirOf(lon: Float, lat: Float): V3 {
    val la = lat * D2R
    val lo = lon * D2R
    return V3(cos(la) * sin(lo), sin(la), cos(la) * cos(lo))
}

/**
 * How far from the planet's centre to stand to see the whole of a country
 * from straight above, with its neighbours round it: the larger of what its
 * height and its width ask for at this [aspect] (width / height of the view),
 * with some air, and never more than 3.2 above the surface. [box] is
 * lon0, lat0, lon1, lat1.
 */
fun heightFor(box: FloatArray, aspect: Float): Float {
    val lat = (box[1] + box[3]) / 2
    val halfW = (box[2] - box[0]) / 2 * cos(lat * D2R) * D2R
    val halfH = (box[3] - box[1]) / 2 * D2R
    val t = tan(FOV / 2 * D2R)
    return 1 + min(max(halfH / t, halfW / (t * aspect)) * 1.6f + 0.12f, 3.2f)
}

/**
 * How far a country's flag must spread from [at] to cover it, in radians: to
 * the farthest point of its [rings] (lon, lat... in degrees) that lies in its
 * [box] (the only part the flag paints), and a hair more so the edge is clean.
 */
fun spanOf(at: V3, box: FloatArray, rings: List<FloatArray>): Float {
    var far = 0f
    for (r in rings) {
        for (k in 0 until r.size / 2) {
            val lon = r[k * 2]
            val lat = r[k * 2 + 1]
            if (lon < box[0] || lon > box[2] || lat < box[1] || lat > box[3]) continue
            far = max(far, at.angleTo(dirOf(lon, lat)))
        }
    }
    return far + 0.01f
}

/**
 * The signal's path from [a] to [b] (unit vectors): the great circle, lifted
 * off the surface by 0.08 plus up to 0.32 for the longest hop, so it arches.
 * x, y, z of [segments] + 1 points, one after another.
 */
fun arcPoints(a: V3, b: V3, segments: Int = 160): FloatArray {
    val ang = a.angleTo(b)
    val h = 0.08f + 0.32f * ang / PI.toFloat()
    var axis = a cross b
    if (axis.length < 1e-6f) axis = V3(0f, 1f, 0f) // the same point, or opposite ones: any way round will do
    axis = axis.normalized()
    val out = FloatArray((segments + 1) * 3)
    for (i in 0..segments) {
        val t = i.toFloat() / segments
        val th = ang * t
        // Rodrigues: a turned about axis by th.
        val c = cos(th)
        val s = sin(th)
        val p = a * c + (axis cross a) * s + axis * ((axis dot a) * (1 - c))
        val r = 1.003f + h * sin(PI.toFloat() * t)
        out[i * 3] = p.x * r
        out[i * 3 + 1] = p.y * r
        out[i * 3 + 2] = p.z * r
    }
    return out
}

/** 0 below [lo], 1 above [hi], an S between. */
fun smoothstep(x: Float, lo: Float, hi: Float): Float {
    if (x <= lo) return 0f
    if (x >= hi) return 1f
    val t = (x - lo) / (hi - lo)
    return t * t * (3 - 2 * t)
}

// -- 4x4 matrices, column-major, as GL wants them -----------------------------------------

object Mat {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun perspective(fovDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(fovDeg * D2R / 2)
        val m = FloatArray(16)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return m
    }

    fun lookAt(eye: V3, target: V3, up: V3): FloatArray {
        val z = (eye - target).normalized()
        val x = (up cross z).normalized()
        val y = z cross x
        val m = FloatArray(16)
        m[0] = x.x; m[4] = x.y; m[8] = x.z
        m[1] = y.x; m[5] = y.y; m[9] = y.z
        m[2] = z.x; m[6] = z.y; m[10] = z.z
        m[12] = -(x dot eye); m[13] = -(y dot eye); m[14] = -(z dot eye)
        m[15] = 1f
        return m
    }

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            o[c * 4 + r] = s
        }
        return o
    }

    /** Rx * Ry * Rz: three.js's default Euler order, which the orbits' tilts were written in. */
    fun rotationXYZ(rx: Float, ry: Float, rz: Float): FloatArray {
        val cx = cos(rx); val sx = sin(rx)
        val cy = cos(ry); val sy = sin(ry)
        val cz = cos(rz); val sz = sin(rz)
        val m = identity()
        m[0] = cy * cz;                m[4] = -cy * sz;               m[8] = sy
        m[1] = cx * sz + sx * sy * cz; m[5] = cx * cz - sx * sy * sz; m[9] = -sx * cy
        m[2] = sx * sz - cx * sy * cz; m[6] = sx * cz + cx * sy * sz; m[10] = cx * cy
        return m
    }

    /** The point [p] carried through [m] (w = 1). */
    fun point(m: FloatArray, p: V3) = V3(
        m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
        m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14])
}
