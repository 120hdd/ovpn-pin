package com.erelay.relay

import com.erelay.relay.data.parseCountries
import com.erelay.relay.data.parseDead
import com.erelay.relay.data.parseExits
import com.erelay.relay.data.parseHosts
import com.erelay.relay.data.parsePinPlan
import com.erelay.relay.data.parsePlace
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes the Go core answers in, parsed. Each sample is the JSON its
 * struct in mobile/core marshals to - field names from the json tags - so a
 * renamed tag there fails here rather than as an empty screen on a phone.
 */
class ModelsTest {

    @Test
    fun countries() {
        val json = """[
          {"code":"de","name":"Germany","alias":["deutschland"],"cities":2,
           "cityList":[
             {"code":"de/fra","country":"de","name":"Frankfurt","nick":"","count":3,"tested":2,"ok":1,
              "ping":120,"load":40,"gbps":10,"p2p":true,"by":{"surfshark":3}},
             {"code":"de/ber","country":"de","name":"Berlin","nick":"Brandenburg","count":1,"tested":0,"ok":0,
              "ping":null,"load":null,"gbps":null,"p2p":null,"by":{"windscribe":1}}],
           "count":4,"by":{"surfshark":3,"windscribe":1},"tested":2,"ok":1,"ping":120,
           "byOk":{"surfshark":1},"byTested":{"surfshark":2},"byPing":{"surfshark":120},"best":1.5},
          {"code":"ru","name":"Russia","alias":[],"cities":1,"cityList":[],"count":2,
           "by":{"surfshark":2},"tested":2,"ok":0,"ping":null,"byOk":{},"byTested":{},"byPing":{},"best":null}
        ]"""
        val got = parseCountries(json)
        assertEquals(2, got.size)
        val de = got[0]
        assertEquals("de", de.code)
        assertEquals(listOf("deutschland"), de.alias)
        assertEquals(120, de.ping)
        assertEquals(mapOf("surfshark" to 3, "windscribe" to 1), de.by)
        assertEquals(2, de.cities.size)
        assertEquals(40, de.cities[0].load)
        assertEquals(true, de.cities[0].p2p)
        assertNull(de.cities[1].ping)
        assertEquals(40, de.lowestLoad)
        assertFalse(de.blocked)
        assertTrue(got[1].blocked)
        assertNull(got[1].ping)
    }

    @Test
    fun badCountriesAreNothingRatherThanACrash() {
        assertTrue(parseCountries("not json").isEmpty())
        assertTrue(parseCountries("{}").isEmpty())
    }

    @Test
    fun exits() {
        val json = """{"ok":true,"exits":[
          {"file":"0.53s-de-fra.prod.surfshark.com.ovpn","city":"fra","cityName":"Frankfurt","nick":"",
           "load":null,"gbps":null,"p2p":null,"provider":"surfshark","host":"de-fra.prod.surfshark.com",
           "ip":"1.2.3.4","ok":true,"ms":530,"why":"","at":1700000000},
          {"file":"x.ovpn","city":"","cityName":"","nick":"","provider":"windscribe","host":"",
           "ip":"","ok":false,"ms":null,"why":"timeout","at":null},
          {"file":"y.ovpn","city":"","cityName":"","nick":"","provider":"surfshark","host":"h",
           "ip":"","ok":null,"ms":null,"why":"","at":null}]}"""
        val got = parseExits(json)
        assertEquals(3, got.size)
        assertEquals(true, got[0].ok)
        assertEquals(530, got[0].ms)
        assertEquals(false, got[1].ok)
        assertEquals("timeout", got[1].why)
        assertNull(got[2].ok)
    }

    @Test
    fun hosts() {
        val json = """{"live":true,"rows":[
          {"host":"example.com","addr":"93.184.216.34:443","app":"Chrome","pid":0,"up":100,"down":2000,
           "hits":3,"live":1,"first":1,"last":2},
          {"host":"","addr":"1.1.1.1:53","app":null,"pid":0,"up":1,"down":2,"hits":1,"live":0,"first":1,"last":1}],
          "total":2,"since":1,"at":2}"""
        val got = parseHosts(json)
        assertEquals(2, got.size)
        assertEquals("Chrome", got[0].app)
        assertEquals(2000L, got[0].down)
        assertNull(got[1].app)
        assertTrue(parseHosts("""{"live":false,"rows":[]}""").isEmpty())
    }

    @Test
    fun places() {
        val p = parsePlace("""{"ip":"62.197.152.150","country":"FR"}""")!!
        assertEquals("62.197.152.150", p.ip)
        assertEquals("fr", p.country)
        assertNull(parsePlace("{}"))
        assertNull(parsePlace("""{"ip":"","country":""}"""))
    }

    @Test
    fun deadExits() {
        val json = """{"ok":true,"folders":[{"path":"/x/pinned","tag":"pinned","label":"pinned","count":2}],
          "exits":[{"file":"a.ovpn","folder":"/x/pinned","tag":"pinned","country":"ru","city":"",
            "provider":"surfshark","ip":"5.6.7.8","why":"refused","at":1}],
          "dead":2,"shelved":[{"tag":"t","label":"earlier","count":1}],"aside":1}"""
        val got = parseDead(json)
        assertEquals(2, got.dead)
        assertEquals(1, got.aside)
        assertEquals("/x/pinned", got.folders[0].path)
        assertEquals("refused", got.exits[0].why)
        assertEquals(listOf("earlier"), got.shelved)
    }

    @Test
    fun pinPlan() {
        val json = """{"folder":"/x/configs","out":"/x/pinned","total":140,"existing":10,"route":"direct",
          "port":0,"maxIps":4,"most":32,"minutes":3,"checked":true,
          "blockers":[{"kind":"empty","say":"Nothing to pin."}],"state":"ready","test":true,"outIsStandard":true}"""
        val got = parsePinPlan(JSONObject(json))
        assertEquals(140, got.total)
        assertEquals(4, got.maxIps)
        assertEquals("Nothing to pin.", got.blockers.single().say)
    }
}
