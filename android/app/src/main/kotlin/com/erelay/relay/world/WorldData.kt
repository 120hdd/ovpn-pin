package com.erelay.relay.world

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The borders of the world, as assets/world.bin holds them (Natural Earth,
 * public domain; the layout is documented in android/tools/world_bin.py).
 *
 * Read once per process and shared: the file is a quarter of a megabyte, and
 * the scene is built again every time its surface is.
 */
class WorldData private constructor(
    /** Each ring as lon, lat, lon, lat... in degrees; closed (the last point joins the first). */
    val rings: Array<FloatArray>,
    val countries: Map<String, Country>,
) {
    class Country(
        val code: String,
        val name: String,
        /** lon0, lat0, lon1, lat1 */
        val box: FloatArray,
        /** The point the camera is brought over: lon, lat. */
        val mid: FloatArray,
        val rings: IntArray,
    )

    val pointCount: Int get() = rings.sumOf { it.size / 2 }

    operator fun get(code: String?): Country? = if (code == null) null else countries[code]

    companion object {
        @Volatile private var shared: WorldData? = null

        /** The bundled world, or null if the asset cannot be read (the home screen then has no map). */
        fun of(context: Context): WorldData? = shared ?: synchronized(this) {
            shared ?: try {
                context.assets.open("world.bin").use { parse(it.readBytes()) }
            } catch (e: Exception) {
                android.util.Log.w("Relay", "world: ${e.message}")
                null
            }?.also { shared = it }
        }

        fun parse(bytes: ByteArray): WorldData {
            require(bytes.size > 16 && String(bytes, 0, 4, Charsets.US_ASCII) == "RLW1") { "not a world.bin" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.position(4)
            val ringCount = b.int
            b.int // total points: the ring table says the same, this is for whoever reads the file by eye
            val countryCount = b.int
            val sizes = IntArray(ringCount) { b.int }
            val rings = Array(ringCount) { i ->
                FloatArray(sizes[i] * 2) { b.short / 100f }
            }
            val countries = HashMap<String, Country>(countryCount * 2)
            repeat(countryCount) {
                val code = String(bytes, b.position(), 2, Charsets.US_ASCII)
                b.position(b.position() + 2)
                val n = b.get().toInt() and 0xFF
                val name = String(bytes, b.position(), n, Charsets.UTF_8)
                b.position(b.position() + n)
                val box = FloatArray(4) { b.float }
                val mid = FloatArray(2) { b.float }
                val k = b.short.toInt() and 0xFFFF
                val idx = IntArray(k) { b.short.toInt() and 0xFFFF }
                countries[code] = Country(code, name, box, mid, idx)
            }
            return WorldData(rings, countries)
        }
    }
}
