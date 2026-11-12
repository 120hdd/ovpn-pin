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

/** The Wi-Fi share as the core reports it: lanShareJSON. */
data class LanShareState(
    val on: Boolean = false,
    val port: Int = 0,
    /** This phone's addresses on the networks it is on; the other device types one. */
    val addrs: List<String> = emptyList(),
    val clients: Int = 0,
)

/** How the app looks and answers a touch, as Settings → Look and feel has it. */
data class Feel(
    val theme: String = "midnight",
    val haptics: Boolean = true,
    val orbTap: Boolean = true,
    val orbMotion: Boolean = true,
    val meter: Boolean = true,
    /** "signal": red, yellow, green. "desktop": app.css's lavender at rest. */
    val orbColors: String = "signal",
)

/** Getting a newly signed-in provider's servers ready: fetch, then pin. */
data class Setup(
    val running: Boolean = false,
    /** "servers", "pinning", "done" or "failed". */
    val step: String = "",
    val provider: String = "",
    val error: String = "",
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
    /** The group the list shows it under - the core's ContinentOf. */
    val continent: String = "Elsewhere",
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
    /** The certificate a server reached by IP serves, SHA-256 hex; learnt on the first Test. */
    val pin: String = "",
    /** "ss" straight to the address over Shadowsocks, "ws" the WebSocket. */
    val transport: String = "ws",
    /** Whether the server's key offered Shadowsocks, so there is a choice to show. */
    val hasSs: Boolean = false,
    /** The Shadowsocks port that exits at the server; the one after it exits at the location. */
    val ssPort: Int = 0,
) {
    val ready get() = domain.isNotEmpty() && hasPassword

    /** Reached by IP address rather than by a domain. */
    val isAddress get() = domain.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
}

/**
 * Split tunnelling: what the tunnel carries, and what it leaves to the line
 * the phone already has.
 *
 * The keys are what the prefs hold and what the core is told, so a grep for
 * "bypass" finds the setting, the interface and the Go that acts on it.
 */
enum class SplitMode(val key: String, val label: String) {
    /** Everything through Relay, as before there was a choice. */
    OFF("off", "Off"),
    /** Everything through Relay but the list, which goes straight out. */
    BYPASS("bypass", "Except these"),
    /** The list through Relay, and everything else straight out. */
    ONLY("only", "Only these");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** Settings → Split tunnel, as the prefs hold it. */
data class SplitPlan(
    val mode: SplitMode = SplitMode.OFF,
    /** The Iranian preset: the core's domains and ranges, and its list of Iranian apps. */
    val iran: Boolean = true,
    /** Package names the user picked. */
    val apps: Set<String> = emptySet(),
    /** Domains, addresses and ranges, each as the core normalised it. */
    val domains: List<String> = emptyList(),
) {
    /** Something is listed by where it goes rather than by which app sends it. */
    val hasDestinations get() = iran || domains.isNotEmpty()

    /** Nothing listed at all: Only these would then carry nothing, so it carries everything. */
    val empty get() = !iran && apps.isEmpty() && domains.isEmpty()
}

/** An app the split tunnel's picker offers: one with an icon in the launcher. */
data class InstalledApp(
    val pkg: String,
    val label: String,
    /** On the core's list of Iranian apps, so the preset covers it while it is on. */
    val iranian: Boolean,
    val system: Boolean,
)

/*
 * Settings → Share with other devices.
 *
 * The user's own server keeps a handful of numbered locations ("slots") for
 * devices that have no Relay of their own - an iPhone, a laptop, a friend's
 * phone - reached with an ordinary V2Ray link. Slot 0 leaves from the server
 * itself; slot k leaves from a provider exit this app finds and pins there.
 * The names below follow the Go side's (core/export.go) where there is one,
 * so a grep for "slot" finds both halves.
 */

/** One location to share, as the chooser picked it: a pick code - "de", "de/fra" - and what to call it. */
data class ExportPick(val code: String, val name: String)

/** One location's part in a run: where it is in the queue, and what was found for it. */
data class ExportStep(
    /** The server's slot this location goes into: its place in the list, plus one. */
    val k: Int,
    val code: String,
    val name: String,
    /** "waiting", "testing", "pinned", "failed" or "skipped". */
    val phase: String = "waiting",
    /** The exit kept, as the core names it. */
    val exit: String = "",
    val provider: String = "",
    /** The upload measured through it from the server, in KB/s; 0 when it was throttled. */
    val upKBs: Int = 0,
    val error: String = "",
)

/** One slot's links, and what stands behind them. */
data class ExportSlotLinks(
    val k: Int,
    /** What the other device lists it as: "Relay · Germany". */
    val name: String,
    /** The place it leaves from, as the screen says it; empty for the server itself. */
    val place: String,
    /** The pick code it was made from; empty for the server itself. */
    val code: String,
    val exit: String = "",
    val provider: String = "",
    val upKBs: Int = 0,
    /** Why it has no links: the location could not be pinned. Empty when it has. */
    val error: String = "",
    val vless: String = "",
    val vmess: String = "",
    /** Shadowsocks, only for the server itself and only when its key offered it. */
    val ss: String = "",
) {
    val ok get() = error.isEmpty() && vless.isNotEmpty()
}

/** What the last run made, kept so it shows again after a restart. */
data class ExportResult(
    /** When it was made, epoch milliseconds. */
    val at: Long,
    val host: String,
    val slots: List<ExportSlotLinks>,
    /** Things that went wrong around the slots rather than in one of them. */
    val notes: List<String> = emptyList(),
)

/** The share screen's whole state. */
data class ExportRun(
    /** A server is set up, and its key says it keeps locations for other devices. */
    val ready: Boolean = false,
    /** How many locations it keeps, besides itself. */
    val slots: Int = 0,
    val running: Boolean = false,
    /** Stop was pressed: the location under test finishes, and nothing after it starts. */
    val stopping: Boolean = false,
    /** "", "starting", "locations", "restoring", "saving" or "links". */
    val phase: String = "",
    /** Which of [steps] is under test, while phase is "locations". */
    val index: Int = 0,
    val steps: List<ExportStep> = emptyList(),
    val last: ExportResult? = null,
    val error: String = "",
)

fun ExportResult.toJson(): String = JSONObject()
    .put("at", at)
    .put("host", host)
    .put("notes", JSONArray(notes))
    .put("slots", JSONArray().apply {
        for (s in slots) put(JSONObject()
            .put("k", s.k).put("name", s.name).put("place", s.place).put("code", s.code)
            .put("exit", s.exit).put("provider", s.provider).put("upKBs", s.upKBs)
            .put("error", s.error).put("vless", s.vless).put("vmess", s.vmess).put("ss", s.ss))
    })
    .toString()

fun parseExportResult(json: String): ExportResult? = try {
    val o = JSONObject(json)
    ExportResult(
        at = o.optLong("at"),
        host = o.optString("host"),
        notes = o.optJSONArray("notes").strings(),
        slots = o.optJSONArray("slots").objects().map { s ->
            ExportSlotLinks(
                k = s.optInt("k"),
                name = s.optString("name"),
                place = s.optString("place"),
                code = s.optString("code"),
                exit = s.optString("exit"),
                provider = s.optString("provider"),
                upKBs = s.optInt("upKBs"),
                error = s.optString("error"),
                vless = s.optString("vless"),
                vmess = s.optString("vmess"),
                ss = s.optString("ss"),
            )
        },
    )
} catch (e: Exception) {
    null
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
            continent = c.optString("continent").ifEmpty { "Elsewhere" },
            alias = c.optJSONArray("alias").strings(),
            cities = c.optJSONArray("cityList").objects().map { t ->
                // The core gives a city's code on its own - "syd" - and a pick
                // needs the country in front of it, "au/syd", the shape the
                // desktop sends. Bare, "syd" read as Syria everywhere a pick is
                // turned into a place, and the race found no servers for it.
                val cc = t.optString("country").ifEmpty { c.optString("code") }
                val own = t.optString("code")
                City(
                    code = if ('/' in own || cc.isEmpty()) own else "$cc/$own",
                    country = cc,
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
