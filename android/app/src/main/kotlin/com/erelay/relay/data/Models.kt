package com.erelay.relay.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * What the Go core says, as Kotlin.
 *
 * The core answers in JSON because gomobile cannot carry a list of structs.
 * The Flutter build passed that JSON straight through to a page; here it is
 * parsed once, at the edge, and nothing above this file sees a JSONObject.
 * Field names follow the Go json tags so a grep finds both halves.
 */

/** The four looks the whole window takes, the desktop's data-state. */
enum class Mode { OFF, BUSY, ON, FAIL }

/** The way out the strip at the top chooses. */
enum class Way(val key: String, val label: String) {
    PROVIDER("surfshark", "Provider"),
    SERVER("single", "Your server"),
    SERVER_EXIT("multi", "Server + exit");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: PROVIDER
    }
}

enum class Sort(val key: String, val label: String) {
    PING("ping", "Ping"), LOAD("load", "Load"), NAME("name", "Name");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: PING
    }
}

/** How the app looks and answers a touch, as Settings → Look and feel has it. */
data class Feel(
    val theme: String = "midnight",
    val haptics: Boolean = true,
    val orbTap: Boolean = true,
    val orbMotion: Boolean = true,
    val meter: Boolean = true,
)

/** An address and the country it is in, as whereAmI and seenAs answer. */
data class Place(val ip: String, val country: String)

/** The tunnel, as the service last published it. */
data class Conn(
    val mode: Mode = Mode.OFF,
    val note: String = "not connected",
    val asked: Int = 0,
    val total: Int = 0,
    val exitAddress: String = "",
    val exitName: String = "",
    val exitProvider: String = "",
    val since: Long = 0,
    /** The core's own word for why, when it failed: all-refused, no-servers... */
    val failure: String? = null,
)

data class Traffic(
    val live: Boolean = false,
    val up: Long = 0,
    val down: Long = 0,
    val upRate: Long = 0,
    val downRate: Long = 0,
    val open: Int = 0,
)

data class City(
    val code: String,
    val country: String,
    val name: String,
    val nick: String,
    val count: Int,
    val tested: Int,
    val ok: Int,
    val ping: Int?,
    val load: Int?,
    val gbps: Int?,
    val p2p: Boolean?,
    val by: Map<String, Int>,
)

data class Country(
    val code: String,
    val name: String,
    val alias: List<String>,
    val cities: List<City>,
    val count: Int,
    val by: Map<String, Int>,
    val tested: Int,
    val ok: Int,
    val ping: Int?,
    val byOk: Map<String, Int>,
    val byTested: Map<String, Int>,
    val byPing: Map<String, Int>,
) {
    /** Tested and nothing answered: the row sinks and says blocked. */
    val blocked get() = tested > 0 && ok == 0
    val lowestLoad get() = cities.mapNotNull { it.load }.minOrNull()
}

data class Exit(
    val file: String,
    val city: String,
    val cityName: String,
    val nick: String,
    val provider: String,
    val host: String,
    val ip: String,
    val ok: Boolean?,
    val ms: Int?,
    val why: String,
)

/** One row of what the log sheet lists: a destination, and who went there. */
data class HostRow(
    val host: String,
    val addr: String,
    val app: String?,
    val up: Long,
    val down: Long,
    val hits: Int,
    val live: Int,
    val last: Long,
)

data class Account(
    val id: String,
    val provider: String,
    val providerName: String,
    val label: String,
    val username: String,
    val active: Boolean,
    val signedIn: Boolean,
)

data class ProviderCard(
    val servers: Int = 0,
    val account: Boolean = false,
    val waiting: Int = 0,
) {
    val usable get() = servers > 0 && account
}

data class Source(val key: String, val name: String, val note: String, val count: Int)

data class TunnelPlan(
    val domain: String = "",
    val hasPassword: Boolean = false,
    val hasApiPassword: Boolean = false,
    val edges: List<String> = emptyList(),
) {
    val ready get() = domain.isNotEmpty() && hasPassword
}

data class DeadRow(
    val file: String, val folder: String, val country: String, val city: String,
    val provider: String, val ip: String, val why: String,
)

data class DeadFolder(val path: String, val label: String, val count: Int)

data class DeadExits(
    val folders: List<DeadFolder> = emptyList(),
    val exits: List<DeadRow> = emptyList(),
    val dead: Int = 0,
    val shelved: List<String> = emptyList(),
    val aside: Int = 0,
)

/** The Windscribe slider puzzle, as two images and where the piece sits. */
data class Captcha(
    val kind: String,
    val art: String,
    val background: String,
    val slider: String,
    val top: Int,
)

data class PinBlocker(val kind: String, val say: String)

data class PinPlan(
    val folder: String = "",
    val out: String = "",
    val total: Int = 0,
    val existing: Int = 0,
    val route: String = "direct",
    val maxIps: Int = 4,
    val minutes: Int = 0,
    val test: Boolean = true,
    val state: String = "",
    val blockers: List<PinBlocker> = emptyList(),
)

/** A pin run in progress, built up from the core's events. */
data class PinRun(
    val running: Boolean = false,
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val reachable: Int = 0,
    val unreachable: Int = 0,
    val forged: Int = 0,
    val last: String = "",
    val finished: String = "",
)

/** One reach test, on one row of the list. */
data class ReachRun(
    val code: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
    val said: String = "",
)

// -- parsing ---------------------------------------------------------------

internal fun JSONObject.intOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)

internal fun JSONObject.boolOrNull(key: String): Boolean? =
    if (isNull(key) || !has(key)) null else optBoolean(key)

internal fun JSONObject.ints(key: String): Map<String, Int> {
    val o = optJSONObject(key) ?: return emptyMap()
    return o.keys().asSequence().associateWith { o.optInt(it) }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }

fun parseCountries(json: String): List<Country> = try {
    JSONArray(json).objects().map { c ->
        Country(
            code = c.optString("code"),
            name = c.optString("name"),
            alias = c.optJSONArray("alias").strings(),
            cities = c.optJSONArray("cityList").objects().map { t ->
                City(
                    code = t.optString("code"),
                    country = t.optString("country"),
                    name = t.optString("name"),
                    nick = t.optString("nick"),
                    count = t.optInt("count"),
                    tested = t.optInt("tested"),
                    ok = t.optInt("ok"),
                    ping = t.intOrNull("ping"),
                    load = t.intOrNull("load"),
                    gbps = t.intOrNull("gbps"),
                    p2p = t.boolOrNull("p2p"),
                    by = t.ints("by"),
                )
            },
            count = c.optInt("count"),
            by = c.ints("by"),
            tested = c.optInt("tested"),
            ok = c.optInt("ok"),
            ping = c.intOrNull("ping"),
            byOk = c.ints("byOk"),
            byTested = c.ints("byTested"),
            byPing = c.ints("byPing"),
        )
    }
} catch (e: Exception) {
    emptyList()
}

fun parseExits(json: String): List<Exit> = try {
    JSONObject(json).optJSONArray("exits").objects().map { e ->
        Exit(
            file = e.optString("file"),
            city = e.optString("city"),
            cityName = e.optString("cityName"),
            nick = e.optString("nick"),
            provider = e.optString("provider"),
            host = e.optString("host"),
            ip = e.optString("ip"),
            ok = e.boolOrNull("ok"),
            ms = e.intOrNull("ms"),
            why = e.optString("why"),
        )
    }
} catch (e: Exception) {
    emptyList()
}

fun parseHosts(json: String): List<HostRow> = try {
    JSONObject(json).optJSONArray("rows").objects().map { r ->
        HostRow(
            host = r.optString("host"),
            addr = r.optString("addr"),
            app = if (r.isNull("app")) null else r.optString("app").ifEmpty { null },
            up = r.optLong("up"),
            down = r.optLong("down"),
            hits = r.optInt("hits"),
            live = r.optInt("live"),
            last = r.optLong("last"),
        )
    }
} catch (e: Exception) {
    emptyList()
}

fun parsePlace(json: String): Place? = try {
    val o = JSONObject(json)
    val ip = o.optString("ip")
    if (ip.isEmpty()) null else Place(ip, o.optString("country").lowercase())
} catch (e: Exception) {
    null
}

fun parseAccounts(array: JSONArray): List<Account> = array.objects().map { a ->
    Account(
        id = a.optString("id"),
        provider = a.optString("provider"),
        providerName = a.optString("providerName"),
        label = a.optString("label"),
        username = a.optString("username"),
        active = a.optBoolean("active"),
        signedIn = a.optBoolean("signedIn"),
    )
}

fun parseDead(json: String): DeadExits = try {
    val o = JSONObject(json)
    DeadExits(
        folders = o.optJSONArray("folders").objects().map {
            DeadFolder(it.optString("path"), it.optString("label"), it.optInt("count"))
        },
        exits = o.optJSONArray("exits").objects().map {
            DeadRow(it.optString("file"), it.optString("folder"), it.optString("country"),
                it.optString("city"), it.optString("provider"), it.optString("ip"),
                it.optString("why"))
        },
        dead = o.optInt("dead"),
        shelved = o.optJSONArray("shelved").objects().map { it.optString("label") },
        aside = o.optInt("aside"),
    )
} catch (e: Exception) {
    DeadExits()
}

fun parseCaptcha(o: JSONObject?): Captcha? = o?.let {
    Captcha(
        kind = it.optString("kind"),
        art = it.optString("art"),
        background = it.optString("background"),
        slider = it.optString("slider"),
        top = it.optInt("top"),
    )
}

fun parsePinPlan(o: JSONObject): PinPlan = PinPlan(
    folder = o.optString("folder"),
    out = o.optString("out"),
    total = o.optInt("total"),
    existing = o.optInt("existing"),
    route = o.optString("route", "direct"),
    maxIps = o.optInt("maxIps", 4),
    minutes = o.optInt("minutes"),
    test = o.optBoolean("test", true),
    state = o.optString("state"),
    blockers = o.optJSONArray("blockers").objects().map {
        PinBlocker(it.optString("kind"), it.optString("say"))
    },
)
