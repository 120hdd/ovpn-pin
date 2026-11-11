package com.erelay.relay

import com.erelay.relay.data.Mode
import com.erelay.relay.world.Stage
import com.erelay.relay.world.V3
import com.erelay.relay.world.WorldData
import com.erelay.relay.world.arcPoints
import com.erelay.relay.world.dirOf
import com.erelay.relay.world.heightFor
import com.erelay.relay.world.spanOf
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's data and its arithmetic, held to the numbers the desktop's atlas.js
 * and make_world.py give. world.bin is read from the asset directory itself:
 * Gradle runs unit tests from the module, and the file is the one that ships.
 */
class WorldTest {

    private val world: WorldData by lazy {
        val f = File("src/main/assets/world.bin")
        assertTrue("run android/tools/world_bin.py first", f.exists())
        WorldData.parse(f.readBytes())
    }

    @Test
    fun theFileHoldsTheWholeWorld() {
        assertEquals(1330, world.rings.size)
        assertEquals(237, world.countries.size)
        assertEquals(65208, world.pointCount)
        // Every ring a country names exists, and is a ring (at least a triangle).
        for (c in world.countries.values) for (i in c.rings) {
            assertTrue("${c.code} ring $i", i in world.rings.indices && world.rings[i].size >= 6)
        }
    }

    @Test
    fun iranRoundTrips() {
        val ir = world["ir"]
        assertNotNull(ir)
        assertEquals("Iran", ir!!.name)
        assertEquals(listOf(44.02f, 25.1f, 63.31f, 39.77f), ir.box.toList())
        assertEquals(54.27f, ir.mid[0], 1e-4f)
        assertEquals(32.58f, ir.mid[1], 1e-4f)
        assertEquals(listOf(616, 617), ir.rings.toList())
        // Its rings lie inside its own box: the mask is painted over the box and would lose land otherwise.
        for (i in ir.rings) {
            val r = world.rings[i]
            for (k in r.indices step 2) {
                assertTrue(r[k] in ir.box[0] - 0.01f..ir.box[2] + 0.01f)
                assertTrue(r[k + 1] in ir.box[1] - 0.01f..ir.box[3] + 0.01f)
            }
        }
    }

    @Test
    fun anUnknownCountryIsNull() {
        assertNull(world["zz"])
        assertNull(world[null])
    }

    @Test
    fun heightMatchesTheDesktopsFormula() {
        val box = world["ir"]!!.box
        // atlas.js heightFor, by hand: half-height 7.335 deg, half-width 9.645 deg * cos(32.435 deg).
        val t = tan(18f * (Math.PI / 180.0).toFloat())
        val halfH = 7.335f * (Math.PI / 180.0).toFloat()
        val halfW = 9.645f * Math.cos(32.435 * Math.PI / 180).toFloat() * (Math.PI / 180.0).toFloat()
        for (aspect in listOf(0.45f, 1f, 1.8f)) {
            val want = 1 + minOf(maxOf(halfH / t, halfW / (t * aspect)) * 1.6f + 0.12f, 3.2f)
            assertEquals(want, heightFor(box, aspect), 1e-3f)
        }
    }

    @Test
    fun aNarrowerViewStandsFurtherBackAndNeverPastTheCeiling() {
        val iran = world["ir"]!!.box
        assertTrue(heightFor(iran, 0.4f) > heightFor(iran, 1.2f))
        // A tiny country is framed close, but not inside the planet; the biggest is capped at 1 + 3.2.
        assertTrue(heightFor(world["va"]!!.box, 0.5f) in 1.1f..1.5f)
        assertEquals(4.2f, heightFor(world["ru"]!!.box, 0.45f), 1e-4f)
    }

    @Test
    fun everyCountrysFillReachesAllOfItsLand() {
        for (c in world.countries.values) {
            val at = dirOf(c.mid[0], c.mid[1])
            val rings = c.rings.map { world.rings[it] }
            val span = spanOf(at, c.box, rings)
            for (r in rings) for (k in 0 until r.size / 2) {
                val lon = r[k * 2]
                val lat = r[k * 2 + 1]
                if (lon < c.box[0] || lon > c.box[2] || lat < c.box[1] || lat > c.box[3]) continue
                assertTrue("${c.code} at $lon,$lat", at.angleTo(dirOf(lon, lat)) < span)
            }
        }
    }

    @Test
    fun aCountrysBoxTakesInItsNearIslandsButNotOverseasLand() {
        assertTrue(world["ca"]!!.box[3] > 82f) // Ellesmere Island
        assertTrue(world["jp"]!!.box[3] > 45f) // Hokkaido
        assertTrue(world["us"]!!.box[0] > -130f) // not Alaska
        assertTrue(world["fr"]!!.box[1] > 40f) // not French Guiana
    }

    @Test
    fun theArcStartsAndEndsOnTheSurfaceAndRisesBetween() {
        val a = dirOf(54.27f, 32.58f)
        val b = dirOf(-98f, 39f)
        val pts = arcPoints(a, b)
        assertEquals(161 * 3, pts.size)
        fun r(i: Int) = hypot(hypot(pts[i * 3], pts[i * 3 + 1]), pts[i * 3 + 2])
        assertEquals(1.003f, r(0), 1e-3f)
        assertEquals(1.003f, r(160), 1e-3f)
        val peak = (0..160).maxOf { r(it) }
        val h = 0.08f + 0.32f * a.angleTo(b) / Math.PI.toFloat()
        assertEquals(1.003f + h, peak, 2e-3f)
        assertEquals(0f, V3(pts[0], pts[1], pts[2]).normalized().angleTo(a), 1e-3f)
    }

    @Test
    fun theHomeScreenFliesIn_arrivesAndGoesHome() {
        val s = Stage(world)
        s.resize(0.46f)
        s.begin(Mode.OFF, "", "", "ir")
        val home = heightFor(world["ir"]!!.box, 0.46f)
        // Eased in from 6 over a couple of seconds, then at rest on the home height.
        assertEquals(6f, s.dist, 0.2f)
        repeat(200) { s.advance(0.02f) }
        assertEquals(home, s.dist, 1e-3f)
        assertEquals("ir", s.fillOCode)
        assertEquals(1f, s.fillO, 1e-3f)

        // Connected to Germany: out to 5, across, and down onto it; its flag filled, home's gone.
        s.update(Mode.ON, "de", "", "ir")
        assertTrue(s.moving())
        repeat(400) { s.advance(0.02f) }
        assertEquals("de", s.fillDCode)
        assertEquals(1f, s.fillD, 1e-3f)
        assertEquals(0f, s.fillO, 1e-3f)
        assertEquals(heightFor(world["de"]!!.box, 0.46f), s.dist, 1e-3f)
        assertNotNull(s.arc)
        assertEquals(1f, s.arc!!.drawn, 1e-3f)
        assertTrue(s.dir.angleTo(dirOf(world["de"]!!.mid[0], world["de"]!!.mid[1])) < 1e-3f)

        // And back.
        s.update(Mode.OFF, "", "", "ir")
        repeat(400) { s.advance(0.02f) }
        assertNull(s.arc)
        assertEquals(1f, s.fillO, 1e-3f)
        assertEquals(0f, s.fillD, 1e-3f)
        assertEquals(home, s.dist, 1e-3f)
    }

    @Test
    fun aFailureStopsHalfwayAndComesHome() {
        val s = Stage(world)
        s.resize(0.46f)
        s.begin(Mode.OFF, "", "de", "ir")
        repeat(200) { s.advance(0.02f) }
        s.update(Mode.BUSY, "", "de", "ir")
        repeat(50) { s.advance(0.02f) }
        assertTrue(s.ripples.any { it.alpha > 0f })
        s.update(Mode.FAIL, "", "de", "ir")
        var peak = 0f
        repeat(150) { s.advance(0.02f); s.arc?.let { peak = maxOf(peak, it.drawn) } }
        assertEquals(0.5f, peak, 0.02f)
        repeat(300) { s.advance(0.02f) }
        assertNull(s.arc)
        assertEquals("ir", s.outlineOCode)
        assertEquals(0f, s.red, 1e-3f)
    }

    @Test
    fun withAnimationsOffEverythingLandsAtOnce() {
        val s = Stage(world)
        s.calm = true
        s.resize(0.46f)
        s.begin(Mode.OFF, "", "", "ir")
        assertTrue(!s.moving())
        assertEquals(heightFor(world["ir"]!!.box, 0.46f), s.dist, 1e-3f)
        s.update(Mode.ON, "de", "", "ir")
        assertTrue(!s.moving())
        assertEquals(1f, s.fillD, 1e-3f)
        assertEquals("de", s.outlineDCode)
        s.update(Mode.BUSY, "", "", "ir")
        assertTrue(!s.moving())
        assertTrue(s.ripples.all { it.alpha == 0f })
    }

    @Test
    fun theCoreSaysUkAndTheMapSaysGb() {
        val s = Stage(world)
        s.resize(0.46f)
        s.begin(Mode.OFF, "", "", "uk")
        assertEquals("gb", s.fillOCode)
    }
}
