package com.erelay.relay.orb

import kotlin.math.PI

/**
 * Where a country sits on the globe, for the turn that brings it into view:
 * a capital for the small ones, a rough centre for the large and the long
 * ones (a hologram of Russia pointed at Moscow would look away from Russia).
 * Degrees, "code lat lon", one per flag in assets/flags.
 */
private const val TABLE = """
ad 42.5 1.5|ae 24.5 54.4|af 34 66|al 41.3 19.8|am 40.2 44.5|aq -80 0|ar -36 -64|at 47.5 14.5
au -26 134|az 40.4 47.8|ba 44 17.8|bd 23.8 90.4|be 50.8 4.4|bg 42.7 25.2|bh 26.1 50.5
bn 4.5 114.7|bo -17 -65|br -10 -52|bs 25 -77.4|bt 27.5 90.4|by 53.7 27.9|bz 17.2 -88.7
ca 56 -100|ch 46.8 8.2|cl -33.4 -70.7|cn 35 104|co 4.6 -74|cr 9.9 -84.1|cy 35.1 33.4
cz 49.8 15.5|de 51 10.4|dk 56 10|do 18.9 -70.2|dz 28 2.6|ec -1.4 -78.4|ee 58.7 25.5
eg 26.8 30|es 40.3 -3.7|fi 62.5 26|fr 46.6 2.3|ge 42 43.5|gl 72 -40|gr 39 22|gt 15.5 -90.3
hk 22.3 114.2|hn 14.8 -86.6|hr 45.1 15.5|hu 47.2 19.5|id -2 118|ie 53.2 -8|il 31.4 35
in 22 79|iq 33 44|ir 32.5 53.7|is 64.9 -18.5|it 42.5 12.5|jm 18.1 -77.3|jo 31 36.5
jp 36 138|ke 0.2 37.9|kg 41.5 74.6|kh 12.6 104.9|kr 36.5 127.9|kz 48 67|la 19 102.5
lk 7.6 80.7|lt 55.3 23.9|lu 49.8 6.1|lv 56.9 24.9|ma 31.8 -6.5|md 47.2 28.5|me 42.7 19.3
mk 41.6 21.7|mm 21 96|mn 46.9 103.8|mo 22.2 113.5|mt 35.9 14.4|mx 23.6 -102.5|my 4.2 102
ng 9.1 8.7|nl 52.2 5.5|no 62 9|np 28.4 84.1|nz -41 173|pa 8.5 -80|pe -9.2 -75
ph 12.9 122|pk 30 70|pl 52 19.4|pr 18.2 -66.5|pt 39.6 -8|py -23.4 -58.4|qa 25.3 51.2
ro 45.9 25|rs 44 20.8|ru 58 60|sa 24 45|se 62.8 16.7|sg 1.35 103.8|si 46.1 14.8
sk 48.7 19.7|sv 13.7 -88.9|th 15.5 101|tn 34 9.5|tr 39 35|tw 23.7 121|ua 49 31.4
uk 54 -2.5|us 39.8 -98.6|uy -32.8 -56|uz 41.4 64.6|ve 7.1 -66.2|vn 16 106|za -29 24
"""

private val DEGREES: Map<String, FloatArray> by lazy {
    TABLE.split('|', '\n').mapNotNull { row ->
        val f = row.trim().split(' ')
        if (f.size == 3) f[0] to floatArrayOf(f[1].toFloat(), f[2].toFloat()) else null
    }.toMap()
}

/** [latitude, longitude] in radians for a two-letter code, or null when the table has none. */
fun countryFocus(code: String): FloatArray? {
    val d = DEGREES[code.lowercase()] ?: return null
    val k = (PI / 180.0).toFloat()
    return floatArrayOf(d[0] * k, d[1] * k)
}
