package com.erelay.relay.world

import com.erelay.relay.data.Mode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * What the home screen's world is doing, and when: the port of the desktop's
 * atlas.js minus its drawing. It holds no GL; [WorldGl] reads the fields below
 * once a frame and paints them, which is also what lets the scene live on
 * while its surface comes and goes.
 *
 * It tells the connection as a journey:
 *   off    straight down onto the country your own address is in, filled with
 *          its flag; every other country is only its border
 *   busy   rings of signal ripple out of it while the servers are asked, and
 *          the camera eases back
 *   on     the camera pulls out until the planet is in view, the signal runs
 *          an arc over it to the country the traffic really leaves from, and
 *          the camera comes down onto that one, which fills with its flag from
 *          where the signal landed; points run the arc at the speed of the
 *          traffic
 *   fail   the signal stops halfway along its arc, flickers red, and the
 *          camera goes home
 * Only one country is ever filled; the neighbours stay outlines.
 *
 * Timings, curves and offsets are atlas.js's, number for number. Everything
 * here runs on the render thread: nothing is shared, so nothing is locked.
 */
class Stage(private val world: WorldData) {

    // -- what the picture reads -------------------------------------------------------

    /** Where the camera hangs: the point above the planet it looks down onto, and how far out. */
    var dir: V3 = dirOf(40f, 25f)
        private set
    var dist = 5f
        private set

    /** How far below the view's middle the country is meant to sit (fraction of the height), set by the layout. */
    var shift = 0f

    /** Width over height of the view, for the height a country is framed from. */
    var aspect = 0.5f
        private set

    /** Journeys are replaced by their end states, and nothing but a still frame is drawn. */
    var calm = false

    /** A country's fill: whose flag, where it lands from, and how far it has spread (0..1). */
    var fillOCode: String? = null; private set
    var fillDCode: String? = null; private set
    var landO = V3(0f, 0f, 1f); private set
    var landD = V3(0f, 0f, 1f); private set
    /** How far each fill spreads from where it lands, in radians: far enough to reach the whole country. */
    var spanO = 0.2f; private set
    var spanD = 0.2f; private set
    var fillO = 0f; private set
    var fillD = 0f; private set

    /** The bright outline round the origin and the destination, and how much of it shows. */
    var outlineOCode: String? = null; private set
    var outlineDCode: String? = null; private set
    var outlineOAlpha = 1f; private set
    var outlineDAlpha = 1f; private set

    /** 0..1 how far the scene has gone red (a failed connection). */
    var red = 0f; private set

    var arc: Arc? = null; private set

    class Ripple { var pos = V3(0f, 0f, 1f); var scale = 0.001f; var alpha = 0f }
    val ripples = Array(3) { Ripple() }

    class Orbit(val radius: Float, val tilt: FloatArray, val speed: Float, val riders: Int, val tone: Int) {
        var spin = tilt[1]
        val u = FloatArray(riders) { it.toFloat() / riders }
        val phase = FloatArray(riders) { Random.nextFloat() * 6f }
        val riderAlpha = FloatArray(riders)
    }

    /** Peri, aqua and orchid: [tone] indexes the palette the painter holds. */
    val orbits = listOf(
        Orbit(1.32f, floatArrayOf(1.1f, 0f, 0.3f), 0.25f, 3, 0),
        Orbit(1.5f, floatArrayOf(-0.6f, 0.8f, -0.2f), -0.18f, 2, 1),
        Orbit(1.68f, floatArrayOf(0.25f, -0.5f, 0.9f), 0.14f, 2, 2),
    )

    /** How visible the orbits are: only once the camera is far enough out to see them. */
    var far = 0f; private set

    /** The signal's path from home to the exit, and the things that run along it. */
    class Arc(val points: FloatArray) {
        val segments = points.size / 3 - 1
        /** How much of it is drawn, 0..1. */
        var drawn = 0f
        var comet = false
        var cometAlpha = 1f
        var opacity = 0.95f
        /** 0 green .. 1 red. */
        var redness = 0f
        val traffic = FloatArray(7)

        /** The point [u] (0..1) of the way along. */
        fun at(u: Float): V3 {
            val f = u.coerceIn(0f, 1f) * segments
            val i = min(f.toInt(), segments - 1)
            val t = f - i
            val a = V3(points[i * 3], points[i * 3 + 1], points[i * 3 + 2])
            val b = V3(points[i * 3 + 3], points[i * 3 + 4], points[i * 3 + 5])
            return a.lerp(b, t)
        }
    }

    // -- the state it is told -----------------------------------------------------------

    private var mode = Mode.OFF
    private var homeCode: String? = null
    private var liveCode: String? = null
    private var pickedCode: String? = null
    private var originCode: String? = null
    private var destCode: String? = null

    // The things the timelines tween, plus the clock the blinking runs on.
    private var orbitSpeed = 1f
    private var blink = 0.5f
    private var traffic = 0f
    private var clock = 0f

    private var journey: Timeline? = null
    private var busy: Timeline? = null
    private var fade: Timeline? = null
    private var rippling = false
    private var rippleClock = 0f
    private var rippleGain = 0f

    /** The country the codes below name, or null: gb is what the map calls the core's uk. */
    private fun norm(code: String?): String? =
        code?.trim()?.lowercase()?.let { if (it == "uk") "gb" else it }?.takeIf { it.length == 2 }

    private fun has(code: String?) = code != null && world[code] != null
    private fun middle(code: String): V3 = world[code]!!.mid.let { dirOf(it[0], it[1]) }
    private fun heightOf(code: String) = heightFor(world[code]!!.box, aspect)

    // -- the way in ---------------------------------------------------------------------

    /**
     * Brings the scene up already knowing what the app knows: where the
     * address is, what is picked, and whether a tunnel is up (then it arrives
     * rather than waits).
     */
    fun begin(mode: Mode, live: String?, picked: String?, home: String?) {
        homeCode = norm(home)
        liveCode = norm(live)
        pickedCode = norm(picked)
        goHome(true)
        follow(mode)
    }

    /** Called whenever anything the scene is told may have changed; cheap when nothing has. */
    fun update(mode: Mode, live: String?, picked: String?, home: String?) {
        liveCode = norm(live)
        pickedCode = norm(picked)
        val h = norm(home)
        if (h != homeCode) home(h)
        if (this.mode == Mode.OFF && !has(homeCode) && has(pickedCode) && originCode != pickedCode) goHome(false)
        follow(mode)
    }

    /** The view's shape changed: a country at rest is framed again for it. */
    fun resize(aspect: Float) {
        if (aspect <= 0f || kotlin.math.abs(aspect - this.aspect) < 0.01f) return
        this.aspect = aspect
        if (!moving() && busy == null) {
            val c = if (mode == Mode.ON) destCode else originCode
            if (c != null) dist = heightOf(c)
        }
    }

    /** True while something travels, so the painter must draw every frame. */
    fun moving(): Boolean = journey?.done == false || busy != null || fade?.done == false

    /** One frame of [dt] seconds. Returns [moving]. */
    fun advance(dt: Float): Boolean {
        val step = min(dt, 0.05f)
        val d = if (calm) 0f else step
        clock += d
        journey?.advance(step)
        busy?.advance(step)
        fade?.advance(step)
        if (journey?.done == true) journey = null
        if (fade?.done == true) fade = null

        far = smoothstep(dist, 2.1f, 3.6f)
        for (o in orbits) {
            o.spin += d * o.speed * 0.2f * orbitSpeed
            for (i in 0 until o.riders) {
                o.u[i] = (o.u[i] + d * o.speed * 0.2f * orbitSpeed + 1f) % 1f
                val b = 0.5f + 0.5f * sin(clock * (2f + blink * 9f) + o.phase[i])
                o.riderAlpha[i] = far * (0.2f + 0.8f * b * b)
            }
        }
        if (rippling) rippleClock += d
        for ((i, r) in ripples.withIndex()) {
            val local = rippleClock - i * 0.53f
            if (local < 0f || rippleGain <= 0f) { r.alpha = 0f; continue }
            val p = (local % 1.6f) / 1.6f
            r.scale = 0.001f + 0.159f * (1f - (1f - p) * (1f - p))
            r.alpha = 0.9f * (1f - p) * rippleGain
        }
        arc?.let { a ->
            for (i in a.traffic.indices) {
                val u = (clock * 0.3f + i.toFloat() / a.traffic.size) % 1f
                a.traffic[i] = if (traffic > 0f) traffic * sin(u * PI.toFloat()) else 0f
            }
        }
        return moving()
    }

    /** Where along the arc traffic dot [i] is. */
    fun trafficAt(i: Int): V3? = arc?.let { a ->
        a.at((clock * 0.3f + i.toFloat() / a.traffic.size) % 1f)
    }

    // -- camera ---------------------------------------------------------------------------

    /**
     * The camera: out along [dir] by [dist], a touch tipped off the vertical so
     * the planet leans a little, its up the local north, looking at a point
     * shifted so that the country lands in the middle band rather than the
     * middle of the screen. Returns view and projection matrices.
     */
    fun camera(): Pair<FloatArray, FloatArray> {
        var side = V3(0f, 1f, 0f) cross dir
        if (side.length < 1e-4f) side = V3(1f, 0f, 0f) // straight over a pole
        side = side.normalized()
        val north = (dir cross side).normalized()
        val pos = (dir + north * -0.035f).normalized() * dist
        val reach = (dist - 1f) * 2f * tan(FOV / 2f * D2R)
        val view = Mat.lookAt(pos, dir + north * (shift * reach), north)
        // The planet's near face is about dist - 1 away: keep the near plane just in front of it,
        // so a 16-bit depth buffer still tells a border from the ground under it.
        val near = ((dist - 1f) * 0.5f).coerceIn(0.02f, 2f)
        return view to Mat.perspective(FOV, aspect, near, dist + 2.5f)
    }

    // -- the fills and outlines -----------------------------------------------------------

    private fun fillAs(origin: Boolean, code: String?, land: V3?) {
        if (!has(code)) {
            if (origin) fillOCode = null else fillDCode = null
            return
        }
        val at = land ?: middle(code!!)
        val c = world[code]!!
        val span = spanOf(at, c.box, c.rings.map { world.rings[it] })
        if (origin) { fillOCode = code; landO = at; spanO = span } else { fillDCode = code; landD = at; spanD = span }
    }

    // -- timelines ------------------------------------------------------------------------

    /** Plays [tl], or - with animations off - lands on its end state this instant. */
    private fun play(tl: Timeline): Timeline? {
        if (calm) { tl.finish(); return null }
        tl.advance(0f)
        return tl
    }

    private fun dropArc() { arc = null }

    /** Whatever was moving stops where it is; the next thing starts from there. */
    private fun stopAll() {
        journey?.kill(); journey = null
        busy?.kill(); busy = null
        fade?.kill(); fade = null
    }

    private fun turnTo(tl: Timeline, to: V3, at: Float, length: Float) {
        val from = dir
        tl.tween(at, length) { p -> dir = from.lerp(to, p).normalized() }
    }

    /**
     * Settle above home - or, before the address is known, above the pick, or
     * over the Middle East from far enough out to be a planet.
     */
    private fun goHome(first: Boolean) {
        stopAll()
        rippling = false
        val code = if (has(homeCode)) homeCode else if (has(pickedCode)) pickedCode else null
        originCode = code
        destCode = null
        if (code != null) fillAs(true, code, null) else fillOCode = null
        outlineOCode = code
        outlineOAlpha = 1f
        outlineDCode = null
        val target = if (code != null) middle(code) else dirOf(45f, 28f)
        val height = if (code != null) heightOf(code) else 4.6f

        val tl = Timeline()
        if (first) {
            dir = target
            dist = 6f
            tl.anim(0.2f, 2.2f, Ease.expoInOut, { dist }, { dist = it }, height)
            fillO = 1f
        } else {
            tl.anim(0f, 0.8f, Ease.power2InOut, { dist }, { dist = it }, max(dist, 3.4f))
            turnTo(tl, target, 0.4f, 1.4f)
            tl.anim(1.4f, 1.1f, Ease.power3InOut, { dist }, { dist = it }, height)
            tl.anim(0f, 0.5f, Ease.power2InOut, { fillD }, { fillD = it }, 0f)
            tl.anim(1.4f, 1f, Ease.power2InOut, { fillO }, { fillO = it }, 1f)
        }
        tl.anim(0f, 0.5f, Ease.power2InOut, { red }, { red = it }, 0f)
        tl.anim(0f, 1f, Ease.power2InOut, { orbitSpeed }, { orbitSpeed = it }, 1f)
        tl.anim(0f, 1f, Ease.power2InOut, { blink }, { blink = it }, 0.5f)
        tl.anim(0f, 1f, Ease.power2InOut, { traffic }, { traffic = it }, 0f)
        tl.call(0.5f) { dropArc() }
        rippleGain = 0f
        journey = play(tl)
    }

    /** Rings of signal out of home while the servers are asked; the camera eases back and the orbits quicken. */
    private fun searching() {
        stopAll()
        val at = if (has(originCode)) middle(originCode!!) else dir
        for (r in ripples) r.pos = at * 1.003f
        val tl = Timeline()
        tl.anim(0f, 2.2f, Ease.sineInOut, { dist }, { dist = it }, dist + 0.35f)
        tl.anim(0f, 0.8f, Ease.power1Out, { orbitSpeed }, { orbitSpeed = it }, 4f)
        tl.anim(0f, 0.8f, Ease.power1Out, { blink }, { blink = it }, 1f)
        if (calm) {
            // A still frame has no rings to ripple; the camera and the orbits just take their places.
            tl.finish()
            return
        }
        rippling = true
        rippleClock = 0f
        rippleGain = 1f
        busy = tl
    }

    /**
     * Out, over, and down onto where the traffic leaves - or, failing, a
     * signal that stops halfway and comes home.
     */
    private fun arrive(code: String?, fail: Boolean) {
        stopAll()
        rippling = false
        if (rippleGain > 0f && !calm) {
            fade = Timeline().also { it.anim(0f, 0.3f, Ease.power2InOut, { rippleGain }, { g -> rippleGain = g }, 0f) }
        } else {
            rippleGain = 0f
        }
        if (!has(code)) { if (fail) goHome(false); return }
        // Where it leaves from, and to: a country to itself has no journey to show - fly, then fill.
        val from = if (has(originCode)) middle(originCode!!) else dir
        val to = middle(code!!)
        destCode = code
        fillAs(false, code, to)
        fillD = 0f
        outlineDCode = code
        outlineDAlpha = 0f
        dropArc()
        val same = from.angleTo(to) < 0.03f
        val a = if (same) null else Arc(arcPoints(from, to))
        arc = a
        if (same && fail) { goHome(false); return }
        if (fail && calm) { goHome(false); return }

        val tl = Timeline()
        tl.anim(0f, 1.3f, Ease.power3InOut, { dist }, { dist = it }, 5f)
        if (a != null) {
            tl.call(0.7f) { a.comet = true }
            val limit = if (fail) 0.5f else 1f
            tl.tween(0.7f, if (fail) 1.2f else 1.9f, if (fail) Ease.power1In else Ease.power1InOut) { p ->
                a.drawn = limit * p
            }
        }
        val start = dir
        tl.tween(0.55f, if (fail) 1.4f else 2.1f) { p ->
            dir = start.lerp(to, (if (fail) 0.45f else 1f) * p).normalized()
        }
        if (fail) {
            // After everything so far: the arc reddens and flickers, the planet dims, then home.
            val t0 = tl.end
            val signal = a!!
            tl.tween(t0, 0.2f) { p -> signal.redness = p }
            tl.tween(t0, 0.42f, Ease.linear) { p -> signal.opacity = 0.95f - 0.8f * yoyo(p, 6) }
            tl.tween(t0, 0.6f) { p -> signal.cometAlpha = 1f - p }
            tl.tween(t0, 0.6f, Ease.linear) { p -> red = 0.7f * yoyo(p, 2) }
            tl.call(tl.end + 1.2f) { goHome(false) }
            journey = play(tl)
            return
        }
        tl.anim(2.3f, 1.5f, Ease.power3InOut, { dist }, { dist = it }, heightOf(code))
        tl.call(2.6f) { a?.comet = false }
        tl.anim(2.4f, 0.4f, Ease.power2InOut, { outlineDAlpha }, { outlineDAlpha = it }, 1f)
        tl.anim(2.6f, 1.3f, Ease.power2Out, { fillD }, { fillD = it }, 1f)
        tl.anim(2.6f, 0.9f, Ease.power2InOut, { fillO }, { fillO = it }, 0f)
        tl.anim(2.6f, 1f, Ease.power2InOut, { orbitSpeed }, { orbitSpeed = it }, 1f)
        tl.anim(2.6f, 1f, Ease.power2InOut, { blink }, { blink = it }, 0.3f)
        tl.anim(2.6f, 1f, Ease.power2InOut, { traffic }, { traffic = it }, 1f)
        if (outlineOCode != null) tl.anim(2.6f, 0.9f, Ease.power2InOut, { outlineOAlpha }, { outlineOAlpha = it }, 0.35f)
        journey = play(tl)
    }

    /** [passes] there-and-back runs over p in 0..1: 0, up to 1, down to 0, ... (an even count ends where it began). */
    private fun yoyo(p: Float, passes: Int): Float {
        val x = p * passes
        val k = min(x.toInt(), passes - 1)
        val f = Ease.power2InOut(x - k)
        return if (k % 2 == 0) f else 1f - f
    }

    /** The exit moved while connected (the check came back with a different country): a short hop, no planet. */
    private fun hop(code: String?) {
        if (!has(code) || code == destCode) return
        destCode = code
        fillAs(false, code, null)
        outlineDCode = code
        outlineDAlpha = 1f
        fillD = 0f
        stopAll()
        val tl = Timeline()
        turnTo(tl, middle(code!!), 0f, 1.2f)
        tl.anim(0f, 1.2f, Ease.power2InOut, { dist }, { dist = it }, heightOf(code))
        tl.anim(0.8f, 1f, Ease.power2Out, { fillD }, { fillD = it }, 1f)
        journey = play(tl)
    }

    // -- what it is told --------------------------------------------------------------------

    private fun follow(now: Mode) {
        if (now == mode) {
            if (now == Mode.ON && liveCode != null && liveCode != destCode) hop(liveCode)
            return
        }
        val was = mode
        mode = now
        when (now) {
            Mode.BUSY -> searching()
            Mode.ON -> arrive(liveCode ?: pickedCode, false)
            Mode.FAIL -> if (was == Mode.BUSY) arrive(pickedCode, true) else goHome(false)
            Mode.OFF -> goHome(false)
        }
    }

    /**
     * The country the user's own address is in, as Cloudflare saw it before
     * anything of ours was in the way.
     */
    private fun home(code: String?) {
        if (!has(code) || code == homeCode) return
        val first = homeCode == null && originCode == null
        homeCode = code
        if (mode == Mode.OFF) { goHome(first); return }
        if (mode == Mode.ON && destCode != null && code != destCode) {
            // The address came back after the route was up: the journey started
            // from a guess, so draw it again from where you really are.
            originCode = code
            fillAs(true, code, null)
            fillO = 0f
            outlineOCode = code
            outlineOAlpha = 0.35f
            val a = Arc(arcPoints(middle(code!!), middle(destCode!!)))
            a.drawn = 1f
            arc = a
        }
    }
}
