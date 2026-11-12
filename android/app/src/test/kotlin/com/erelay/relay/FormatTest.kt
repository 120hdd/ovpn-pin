package com.erelay.relay

import com.erelay.relay.ui.bytes
import com.erelay.relay.ui.countryName
import com.erelay.relay.ui.countryOfPick
import com.erelay.relay.ui.flagOf
import com.erelay.relay.ui.nameOfPick
import com.erelay.relay.ui.rate
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun sizesAreBase1024ToThreeFigures() {
        assertEquals("0 B", bytes(0))
        assertEquals("1023 B", bytes(1023))
        assertEquals("1.00 KB", bytes(1024))
        assertEquals("1.50 KB", bytes(1536))
        assertEquals("15.0 KB", bytes(15 * 1024))
        assertEquals("150 KB", bytes(150 * 1024))
        assertEquals("1.00 MB", bytes(1024L * 1024))
    }

    @Test
    fun aQuietLineIsIdle() {
        assertEquals("idle", rate(0))
        assertEquals("idle", rate(511))
        assertEquals("512 B/s", rate(512))
        assertEquals("2.00 KB/s", rate(2048))
    }

    @Test
    fun flagsAreRegionalIndicators() {
        assertEquals("🇩🇪", flagOf("de"))
        // The core folds gb into uk; the emoji only knows GB.
        assertEquals(flagOf("gb"), flagOf("uk"))
        assertEquals("", flagOf(""))
        assertEquals("", flagOf("1x"))
    }

    @Test
    fun countryNames() {
        assertEquals("Germany", countryName("de"))
        assertEquals("United Kingdom", countryName("uk"))
        assertEquals("", countryName(""))
    }

    @Test
    fun theFourShapesOfAPick() {
        val names = mapOf("fr" to "France")
        assertEquals("Fastest available", nameOfPick("auto", names))
        assertEquals("France", nameOfPick("fr", names))
        assertEquals("France · Windscribe", nameOfPick("fr:windscribe", names))
        assertEquals("Par, France", nameOfPick("fr/par", names))
        assertEquals("fr-par.prod.surfshark.com", nameOfPick("file:1.23s-fr-par.prod.surfshark.com.ovpn", names))

        assertEquals("", countryOfPick("auto"))
        assertEquals("fr", countryOfPick("fr/par:surfshark"))
        assertEquals("fr", countryOfPick("file:1.23s-fr-par.prod.surfshark.com.ovpn"))
    }
}
