package com.erelay.relay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import relay.Client
import relay.ReachProgress
import relay.Relay

/**
 * The Kotlin end of the bridge that lets `app/ui` run unchanged.
 *
 * Everything crossing it is JSON text, in both directions. That is not
 * laziness: the page parses JSON whichever way it arrives, gomobile cannot
 * carry a slice of structs at all, and a shape mapped through Dart and Kotlin
 * and Go is a shape three languages have to be kept in step about by hand. One
 * encode in Go, one parse in the page, and nothing in between needs to know
 * what a country row looks like.
 *
 * [RelayVpnService] still owns the tunnel. A tunnel whose lifetime is tied to
 * an Activity dies when the window does, and closing the window is not
 * disconnecting.
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val CONTROL = "relay/control"
        private const val STATUS = "relay/status"
        private const val VPN_REQUEST = 1
        private const val PICK_FOLDER = 2
        private const val PREFS = "relay"
    }

    private var statusSink: EventChannel.EventSink? = null
    private var pendingCountry = "auto"

    /**
     * Flutter answers a MethodChannel on the main thread and throws if it is
     * answered anywhere else, so every deferred reply comes back through
     * here. One handler, made once, rather than a Looper lookup per answer.
     */
    private val main = Handler(Looper.getMainLooper())

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = pushStatus()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Before anything can ask for a list. Both this and the service call
        // it, because either may be the first thing alive.
        AppFiles.announce(applicationContext)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONTROL)
            .setMethodCallHandler { call, result ->
                if (call.method != "call") {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
                val name = call.argument<String>("name").orEmpty()
                val args = call.argument<List<Any?>>("args") ?: emptyList()
                answer(name, args, result)
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, STATUS)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    statusSink = events
                    pushStatus()
                }

                override fun onCancel(arguments: Any?) {
                    statusSink = null
                }
            })
    }

    /**
     * One page call, answered now or later.
     *
     * A [Bridge.Later] holds the result open while the work runs off the main
     * thread and settles it when there is something true to settle it with.
     * Exactly one of the two callbacks fires, which is what Flutter requires
     * of a Result and what [Bridge.work] guarantees.
     */
    private fun answer(name: String, args: List<Any?>, result: MethodChannel.Result) {
        try {
            when (val out = Bridge.call(this, name, args)) {
                is Bridge.Later -> out.start(
                    { json -> main.post { result.success(json) } },
                    { e -> main.post { refuse(result, e) } })
                else -> result.success(out as String?)
            }
        } catch (e: Throwable) {
            refuse(result, e)
        }
    }

    /**
     * A refusal the page can print.
     *
     * "not-here" is the code Dart turns into a rejected promise carrying the
     * sentence; the page shows that sentence. Anything else is a fault rather
     * than an answer and says so.
     */
    private fun refuse(result: MethodChannel.Result, e: Throwable) {
        if (e is Bridge.NotHere) {
            result.error("not-here", e.message, null)
        } else {
            Log.w(RelayVpnService.TAG, "bridge", e)
            result.error("failed", e.message ?: e.toString(), null)
        }
    }

    // -- pickers ---------------------------------------------------------------
    //
    // A folder picker is the same shape as a slow network call - the answer
    // is not ready when the page asks - except that what it waits for is a
    // person rather than a socket. So it reuses Bridge.Later, with the result
    // held here until Android comes back with a URI.

    private class Picker(
        val answer: (String?) -> Unit,
        val fail: (Throwable) -> Unit,
        val done: (android.net.Uri?) -> String,
    )

    private val pickers = HashMap<Int, Picker>()

    private fun pick(
        code: Int, intent: Intent, done: (android.net.Uri?) -> String,
    ): Bridge.Later = Bridge.Later { answer, fail ->
        if (pickers.containsKey(code)) {
            // Two pickers at once is one of them silently losing its result.
            fail(Bridge.NotHere("a picker is already open"))
        } else {
            pickers[code] = Picker(answer, fail, done)
            try {
                startActivityForResult(intent, code)
            } catch (e: Exception) {
                pickers.remove(code)
                fail(e)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, statusReceiver, IntentFilter(RelayVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startMeter()
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        stopMeter()
        super.onStop()
    }

    // -- the meter ------------------------------------------------------------
    //
    // Once a second while a window is open, and not at all while it is not.
    // The desktop pushes this from a thread in the engine; here it is the
    // Activity's job, because the numbers are only ever drawn by a page and a
    // page only exists while the window does. A tunnel running with no window
    // should be spending its battery carrying traffic, not counting it for
    // nobody.

    private val meter = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (statusSink != null) push("Traffic", traffic())
            meter.postDelayed(this, 1000)
        }
    }

    private fun startMeter() {
        meter.removeCallbacks(tick)
        meter.postDelayed(tick, 1000)
    }

    private fun stopMeter() = meter.removeCallbacks(tick)

    // -- the list this window is looking at ------------------------------------
    //
    // One catalogue, built once and kept, rather than a fresh scan per call.
    //
    // Every question the page asks about the list - what countries are there,
    // what is in this one, how big is that pool - used to build a Client and
    // rescan the folder for itself. Four hundred files read to open a country.
    // Worse, a scan cannot hold a narrowing, so "Starred" had nowhere to live.

    private var cat: Client? = null

    @Synchronized
    private fun catalogue(): Client {
        cat?.let { return it }
        val c = Relay.newClient("", "")
        try {
            c.load()
        } catch (e: Exception) {
            // An empty folder is not an error - it is a phone nobody has put
            // configs on yet, and the window has a whole screen for saying so.
            Log.i(RelayVpnService.TAG, "nothing to read yet: ${e.message}")
        }
        narrow(c)
        cat = c
        return c
    }

    /** After anything that changes which files are there or what is known. */
    @Synchronized
    private fun reload() {
        cat = null
    }

    /**
     * Which pool this window is connecting out of.
     *
     * Only two on a phone. Everything is the folder; Starred is a handful of
     * exits picked out by hand, which is a set of filenames rather than a
     * folder - so it is a narrowing rather than a different place to read.
     */
    private fun source(): String = prefs.getString("source", "all") ?: "all"

    private fun narrow(c: Client) {
        if (source() != "starred") return
        val files = starredFiles(c)
        if (files.isEmpty()) return
        try {
            c.setOnly(files)
        } catch (e: Exception) {
            Log.w(RelayVpnService.TAG, "starred: ${e.message}")
        }
    }

    /** The filenames behind every starred place, one per line. */
    private fun starredFiles(c: Client): String {
        val out = StringBuilder()
        for (code in favouriteSet()) {
            val names = try {
                c.poolFiles(code)
            } catch (e: Exception) {
                ""
            }
            if (names.isNotBlank()) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(names)
            }
        }
        return out.toString()
    }

    // -- where the files are ---------------------------------------------------

    private fun pinnedDir() = AppFiles.pinned(applicationContext)
    private fun authFile() = AppFiles.auth(applicationContext)

    private fun configCount() = AppFiles.count(pinnedDir())

    // -- what the page asks for ------------------------------------------------

    /**
     * boot() and info(), which the page calls once on the way up and again
     * whenever the folder might have changed.
     *
     * The country list comes out of Go already encoded, so it is spliced in as
     * text rather than parsed and rebuilt. Every other key is present even
     * when the phone has nothing to say about it: the page reads `systemProxy
     * !== false` and friends, and a missing key is not the same as a false one.
     */
    fun describe(): String {
        val c = catalogue()
        val countries = try {
            c.countriesJSON()
        } catch (e: Exception) {
            "[]"
        }

        val o = JSONObject()
        o.put("serverCount", c.count().toInt())
        o.put("folder", pinnedDir().absolutePath)
        o.put("folders", JSONArray().put(pinnedDir().absolutePath))
        o.put("providers", JSONArray().put("surfshark").put("windscribe"))
        o.put("providerState", providerState())
        // There is no system proxy on a phone and no port to choose: the tun
        // carries everything. Said as false rather than omitted so the page
        // draws the control off rather than defaulting it on.
        o.put("systemProxy", false)
        o.put("port", 0)
        o.put("defaultPort", 0)
        o.put("picked", prefs.getString("picked", "auto"))
        o.put("favourites", JSONArray(favouriteSet().toList()))
        o.put("sortBy", prefs.getString("sortBy", "ping"))
        o.put("keepOnClose", true)
        o.put("hasCredentials", hasAuth())
        // Which way out is chosen, and what the server half knows. The strip
        // at the top of the window reads `mode`; the settings sheet reads
        // `tunnel`. Same two keys the desktop sends, so the page needs no
        // branch for which machine it is running on.
        o.put("mode", prefs.getString("mode", "surfshark"))
        // Whether the local network is spared. A phone thing: the desktop
        // sets a system proxy and never touched the LAN in the first place.
        o.put("allowLan", prefs.getBoolean("allowLan", true))
        o.put("tunnel", JSONObject(tunnelPlan()))
        o.put("username", credentials()?.first ?: "")
        o.put("about", "Relay  -  ${pinnedDir().absolutePath}")
        // What boot() reads to decide whether to draw a live connection. The
        // desktop sends `on`; anything else is off, and the page checks for
        // that exact word.
        o.put("status", JSONObject(statusMap()))
        o.put("recovered", false)

        // Spliced rather than parsed: both of these are already the JSON the
        // page wants, and decoding them here only to encode them again would
        // be two parses of a hundred kilobytes for no change to a single byte.
        return o.toString().dropLast(1) +
            ",\"sources\":${sources()}" +
            ",\"countries\":$countries}"
    }

    /**
     * What each provider is worth here: how many exits it has, whether there
     * is an account for it, and how many configs are waiting to be pinned.
     *
     * The page's cards read all three - `provStep` asks for a sign-in, a
     * fetch, a pin or an update in that order - so a phone that answered only
     * the first two would show "Get servers" forever over a folder of configs
     * that had already been fetched.
     */
    private fun providerState(): JSONObject {
        val ctx = applicationContext
        var surfshark = 0
        var windscribe = 0
        for (name in pinnedDir().list() ?: emptyArray<String>()) {
            if (!name.endsWith(".ovpn")) continue
            if (name.contains(".ws.")) windscribe++ else surfshark++
        }
        // Signed in, rather than "a file exists". The roster is what the
        // account pane draws, and the cards have to agree with it.
        var ssAccount = false
        var wsAccount = false
        val roster = Accounts.listing(ctx)
        for (i in 0 until roster.length()) {
            val a = roster.getJSONObject(i)
            if (!a.optBoolean("signedIn")) continue
            if (a.optString("provider") == Accounts.WINDSCRIBE) {
                wsAccount = true
            } else {
                ssAccount = true
            }
        }

        return JSONObject().apply {
            put("surfshark", JSONObject().apply {
                put("servers", surfshark)
                put("account", ssAccount)
                put("waiting", AppFiles.waiting(AppFiles.configs(ctx), ".prod."))
                put("usable", surfshark > 0 && ssAccount)
            })
            put("windscribe", JSONObject().apply {
                put("servers", windscribe)
                put("account", wsAccount)
                put("waiting", AppFiles.waiting(AppFiles.windscribe(ctx), ".ws."))
                put("usable", windscribe > 0 && wsAccount)
            })
        }
    }

    fun statusMap(): String = JSONObject().apply {
        val up = RelayVpnService.exitAddress.isNotEmpty()
        // Two words for the same thing, because two readers want different
        // ones: the status sheet reads `state`, and boot() checks for the
        // desktop's own "on".
        put("state", if (up) "on" else if (RelayVpnService.total > 0) "working" else "off")
        put("connected", up)
        put("exit", RelayVpnService.exitAddress)
        put("country", RelayVpnService.exitName)
        put("since", RelayVpnService.since)
        put("note", RelayVpnService.status)
    }.toString()

    /**
     * The page calls this and ignores what comes back: the answer arrives as
     * an onRealIp event, whenever the lookup finishes. Returning the address
     * here and never emitting is why the window said "checking..." forever.
     *
     * Off the main thread, because it is a TLS handshake to another continent
     * and Android kills an app that does one on the UI thread.
     */
    fun whoami(): String {
        Thread {
            val json = Relay.whereAmIJSON(8000L)
            main.post { push("RealIp", json) }
        }.start()
        return "{}"
    }

    /**
     * The independent check, run through the tunnel and pushed at the page
     * when it answers.
     *
     * The window will not put anything under "Seen as" until this arrives,
     * and it is right not to: the exit's proxy address and its egress address
     * are different numbers, and showing the first would be a claim nothing
     * checked.
     */
    fun seenAs(): String {
        val addr = RelayVpnService.exitAddress
        val name = RelayVpnService.exitName
        // Asked with the winning exit's own provider credential. The other
        // one is refused, and a refusal here reads on screen as a tunnel
        // that cannot say where it comes out.
        val creds = when (RelayVpnService.exitProvider) {
            Accounts.WINDSCRIBE -> windscribeCredentials()
            else -> credentials()
        }
        if (addr.isEmpty() || creds == null) return "{}"

        Thread {
            val seen = Relay.seenAsJSON(addr, name, creds.first, creds.second, 15000L)
            main.post { pushConnected(seen) }
        }.start()
        return "{}"
    }

    /**
     * The meter: four numbers, pushed once a second while a route is up.
     *
     * Summed from the same rows the log sheet lists, so the two can never
     * disagree about how much went through - which they would if each
     * counted for itself.
     */
    fun traffic(): String {
        val hosts = RelayVpnService.hostsJSON()
        var up = 0L
        var down = 0L
        var liveCount = 0
        try {
            val o = JSONObject(hosts)
            val rows = o.optJSONArray("rows")
            if (rows != null) {
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    up += r.optLong("up")
                    down += r.optLong("down")
                    liveCount += r.optInt("live")
                }
            }
        } catch (e: Exception) {
            // A meter that throws would stop the second-by-second push. Zero
            // is wrong and quiet; an exception here is wrong and loud.
        }
        // The rates, which the page draws rather than the totals. Worked out
        // here from two readings rather than counted, because a rate is a
        // difference over a time and neither half is a thing the core knows -
        // it counts bytes, and it has no opinion about when it was last asked.
        val now = System.nanoTime()
        val seconds = if (lastTrafficAt == 0L) 0.0
                      else (now - lastTrafficAt) / 1e9
        val upRate = if (seconds > 0.05) ((up - lastUp) / seconds).toLong() else 0L
        val downRate = if (seconds > 0.05) ((down - lastDown) / seconds).toLong() else 0L
        lastTrafficAt = now
        lastUp = up
        lastDown = down

        return JSONObject().apply {
            // The page draws nothing at all without this, whatever the
            // numbers say: `if (!t || !t.live ...) flux.stop()`.
            put("live", RelayVpnService.exitAddress.isNotEmpty())
            put("up", up)
            put("down", down)
            put("upRate", maxOf(0L, upRate))
            put("downRate", maxOf(0L, downRate))
            put("open", liveCount)
            put("since", RelayVpnService.since)
        }.toString()
    }

    // The previous reading, for working out a rate. Only ever touched from
    // traffic(), which the page calls on one timer.
    private var lastTrafficAt = 0L
    private var lastUp = 0L
    private var lastDown = 0L

    /** What went where, pulled while the log sheet is open. */
    fun hosts(): String = RelayVpnService.hostsJSON()

    /**
     * The individual exits behind one row of the list, with what is known
     * about each.
     *
     * The page sends the row's country - which may carry a city, as `fr/par`
     * - and its provider separately, because that is the shape main.py takes.
     * Joined back into one code here, because the core has one parser for it
     * and two would drift.
     *
     * This used to ignore both arguments and hand back the country list
     * instead. The page read `r.exits`, found nothing, and drew an empty
     * panel under every country on the phone.
     */
    fun exitsIn(country: String, provider: String?): String {
        val code = if (provider.isNullOrEmpty()) country else "$country:$provider"
        return catalogue().exitsInJSON(code)
    }

    // -- the connection --------------------------------------------------------

    /**
     * The page does `if (!r.ok)`, so an answer without an `ok` in it is a
     * TypeError rather than a refusal - which is exactly what happened:
     * "Cannot read properties of null (reading 'ok')" at app.js:1397, and a
     * button that stayed on "Cancel" with nothing behind it.
     */
    fun beginConnect(country: String): String {
        pendingCountry = country
        // Android's own consent dialog. prepare() returns null once it has
        // been given, which is why this is not an unconditional launch.
        val consent = VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, VPN_REQUEST)
            // Started, as far as the page is concerned. The consent dialog is
            // part of connecting, and a false here would put the button back
            // to "Connect" while Android is still asking.
            return """{"ok":true}"""
        }
        startService(pendingCountry)
        return """{"ok":true}"""
    }

    @Deprecated("startActivityForResult, kept because VpnService.prepare uses it")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        pickers.remove(requestCode)?.let { picker ->
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            // Off the main thread: copying four hundred configs through a
            // content resolver is not something to do while the window is
            // trying to draw the dialog closing.
            Bridge.work { picker.done(uri) }.start(picker.answer, picker.fail)
            return
        }

        if (requestCode != VPN_REQUEST) return
        if (resultCode == Activity.RESULT_OK) {
            startService(pendingCountry)
        } else {
            RelayVpnService.report(this, "failed: permission refused")
        }
    }

    private fun startService(country: String): String? {
        val intent = Intent(this, RelayVpnService::class.java)
            .setAction(RelayVpnService.ACTION_START)

        // Which way out, decided here rather than in the service, because
        // this is where the settings live and the service should be handed
        // everything it needs rather than reach back for it.
        val provider = prefs.getString("mode", "surfshark") ?: "surfshark"

        if (provider == "surfshark") {
            if (configCount() == 0) throw Bridge.NotHere(
                "nothing pinned. Push configs to ${pinnedDir().absolutePath}"
            )
            // One is enough to start. core.Logins.Keep drops the exits
            // there is nothing to open and only refuses when that leaves
            // nothing at all, so a phone signed into one provider still
            // connects through the half it has.
            val creds = credentials() ?: windscribeCredentials() ?: throw Bridge.NotHere(
                "no credentials. Add an account in Settings, or put a username on " +
                    "one line and a password on the next in " + authFile().absolutePath
            )
            intent
                .putExtra(RelayVpnService.EXTRA_PROVIDER, "surfshark")
                .putExtra(RelayVpnService.EXTRA_USER, creds.first)
                .putExtra(RelayVpnService.EXTRA_PASSWORD, creds.second)
                .putExtra(RelayVpnService.EXTRA_CONFIG_DIR, pinnedDir().absolutePath)
                .putExtra(RelayVpnService.EXTRA_COUNTRY, country)
            // The other provider's, when there is one. A folder holding both
            // races both; a folder holding only Surfshark ignores this.
            windscribeCredentials()?.let { (wsUser, wsPassword) ->
                intent.putExtra(RelayVpnService.EXTRA_WS_USER, wsUser)
                    .putExtra(RelayVpnService.EXTRA_WS_PASSWORD, wsPassword)
            }
            // Starred is a set of filenames rather than a folder, so the
            // service is handed the names. Sent even when empty, because the
            // absence of the extra and an empty one mean the same thing and
            // the service reads it that way.
            if (source() == "starred") {
                intent.putExtra(RelayVpnService.EXTRA_ONLY, starredFiles(catalogue()))
            }
        } else {
            val domain = prefs.getString("tunnelDomain", "").orEmpty()
            val password = prefs.getString("tunnelPassword", "").orEmpty()
            if (domain.isEmpty() || password.isEmpty()) throw Bridge.NotHere(
                "no server set. Put the domain and password in Settings first."
            )
            intent
                .putExtra(RelayVpnService.EXTRA_PROVIDER, provider)
                .putExtra(RelayVpnService.EXTRA_DOMAIN, domain)
                .putExtra(RelayVpnService.EXTRA_TUNNEL_PASSWORD, password)
                .putExtra(RelayVpnService.EXTRA_EDGES,
                    prefs.getString("tunnelEdges", "").orEmpty())
        }

        ContextCompat.startForegroundService(this, intent)
        return null
    }

    /** The page reads `r && r.session`, so null is a fine answer here. */
    fun stopTunnel(): String {
        startService(
            Intent(this, RelayVpnService::class.java).setAction(RelayVpnService.ACTION_STOP)
        )
        return "null"
    }

    fun cancelRace(): String = stopTunnel()

    // -- which pool to connect out of ------------------------------------------

    /**
     * The pools worth offering, with what each one holds.
     *
     * Two on a phone. The desktop has more because it has more folders - one
     * per site it swept, one for what it verified - and a phone has one
     * folder and a set of stars. A row is only offered when something is
     * actually in it: a source with nothing behind it is a way to empty the
     * list and then wonder why.
     */
    fun sources(): String {
        val c = catalogue()
        val rows = JSONArray()

        val all = counts(c, "")
        if (all.optInt("exits") > 0) {
            rows.put(JSONObject().apply {
                put("key", "all")
                put("name", "Everything")
                put("note", "${all.optInt("exits")} exits · every folder the app reads")
                put("count", all.optInt("places"))
                put("exits", all.optInt("exits"))
            })
        }

        val starred = counts(c, starredFiles(c))
        if (favouriteSet().isNotEmpty() && starred.optInt("exits") > 0) {
            rows.put(JSONObject().apply {
                put("key", "starred")
                put("name", "Starred")
                put("note", "${starred.optInt("exits")} exits · only the places you picked out")
                put("count", starred.optInt("places"))
                put("exits", starred.optInt("exits"))
            })
        }

        // A source whose exits have since gone reads as Everything rather than
        // as a name with nothing behind it.
        var here = source()
        var known = false
        for (i in 0 until rows.length()) {
            if (rows.getJSONObject(i).optString("key") == here) known = true
        }
        if (!known) here = "all"

        return JSONObject().apply {
            put("ok", true)
            put("rows", rows)
            put("source", here)
        }.toString()
    }

    private fun counts(c: Client, files: String): JSONObject = try {
        JSONObject(c.countsJSON(files))
    } catch (e: Exception) {
        JSONObject()
    }

    /** Connect out of this pool from now on. */
    fun setSource(source: String?): String {
        // The source sheet's Browse button imports a folder and then asks for
        // `folder:<path>`. On a phone there is one folder and everything is
        // already in it, so that is Everything under another name - and
        // refusing it would mean an import that worked and a sheet that said
        // it had not.
        var key = source ?: "all"
        if (key.startsWith("folder:")) key = "all"
        if (key != "all" && key != "starred") {
            return refusal("There is nothing in that one.")
        }
        if (key == "starred" && starredFiles(catalogue()).isBlank()) {
            return refusal("There is nothing in that one.")
        }
        prefs.edit().putString("source", key).apply()
        reload()

        val c = catalogue()
        val o = JSONObject().apply {
            put("ok", true)
            put("source", key)
            put("folder", pinnedDir().absolutePath)
            put("folders", JSONArray().put(pinnedDir().absolutePath))
            put("serverCount", c.count().toInt())
        }
        return o.toString().dropLast(1) + ",\"countries\":${c.countriesJSON()}}"
    }

    /**
     * Configs, in from a folder somebody picked.
     *
     * The desktop points itself at a folder and reads it where it stands. A
     * phone cannot: a picked folder is a content URI rather than a path, and
     * the Go core has to be handed a path. So they are copied in, and the app
     * goes on reading its own folder.
     *
     * This is the call that makes a cable optional. Everything else about
     * setting the phone up can be done from the window; before this, the
     * configs could only arrive by `adb push`.
     */
    fun chooseFolder(): Bridge.Later {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return pick(PICK_FOLDER, intent) { uri ->
            if (uri == null) {
                // Cancelled. The page checks r.ok and says nothing when there
                // is no error to say, which is right - a person who closed
                // the picker knows they closed it.
                """{"ok":false}"""
            } else {
                val got = Import.fromTree(applicationContext, uri)
                reload()
                if (got.seen == 0) {
                    refusal("No .ovpn files in that folder, so it was not used.")
                } else {
                    JSONObject().apply {
                        put("ok", true)
                        put("folder", pinnedDir().absolutePath)
                        put("count", configCount())
                        put("copied", got.copied)
                        put("skipped", got.skipped)
                    }.toString()
                }
            }
        }
    }

    /** Back to reading everything, which is the only folder there is. */
    fun resetFolder(): String {
        prefs.edit().putString("source", "all").apply()
        reload()
        return JSONObject().apply {
            put("ok", true)
            put("folder", pinnedDir().absolutePath)
            put("source", "all")
            put("folders", JSONArray().put(pinnedDir().absolutePath))
        }.toString()
    }

    // -- the ones that stopped answering ---------------------------------------

    /**
     * Ask the exits behind one row whether they answer, and keep the answer.
     *
     * One row at a time. Asking all four hundred was a single button in the
     * header and it was the wrong shape twice over: nobody wants to know
     * about ninety countries, and a run that long is where the line starts
     * dropping connections and the answers stop being about the exits.
     *
     * The count comes back at once and everything else arrives as events,
     * because the page shows each result on its own row as it lands.
     */
    @Synchronized
    fun testReach(code: String?): String {
        val row = code.orEmpty()
        if (testing != null) return refusal("busy")

        val c = catalogue()
        val pool = try {
            c.poolFiles(row)
        } catch (e: Exception) {
            ""
        }
        if (pool.isBlank()) return refusal("Nothing to test.")
        val total = pool.lines().count { it.isNotBlank() }

        testing = row
        Thread {
            val answer = try {
                JSONObject(c.testReach(row, 5000L, 12L, object : ReachProgress {
                    override fun onReach(payload: String) {
                        main.post { push("Reach", payload) }
                    }
                }))
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: e.toString())
            }
            testing = null
            // The verdicts are on disk now, so the list is a different list.
            reload()
            val countries = try {
                catalogue().countriesJSON()
            } catch (e: Exception) {
                "[]"
            }
            answer.put("code", row)
            val payload = answer.toString().dropLast(1) + ",\"countries\":$countries}"
            main.post { push("ReachDone", payload) }
        }.start()

        return JSONObject().put("ok", true).put("total", total).toString()
    }

    /** Pressing the bolt again stops the run rather than queueing behind it. */
    fun cancelReach(): String {
        try {
            catalogue().cancelReach()
        } catch (e: Exception) {
            Log.w(RelayVpnService.TAG, "cancel: ${e.message}")
        }
        return JSONObject().put("ok", true).toString()
    }

    /** Which row is being tested, or nothing. */
    @Volatile private var testing: String? = null

    /**
     * What would be set aside, where from and why - without anything going.
     *
     * The page asks for this on the way up and hides its header mark when
     * both counts are zero, so it is answered rather than refused: a refusal
     * would leave the mark drawn over nothing.
     */
    fun deadExits(): String = catalogue().deadExitsJSON()

    /**
     * Move the dead ones out of the folders that were ticked.
     *
     * The page sends the paths it was offered. The core checks them against
     * what it just offered rather than trusting them - this is the one call
     * that moves somebody configs about.
     */
    fun dropExits(folders: List<*>?): String {
        val picked = (folders ?: emptyList<Any?>())
            .mapNotNull { it as? String }.joinToString("\n")
        return withList(catalogue().dropExits(picked))
    }

    /** Put them back where they came from. */
    fun restoreDropped(tags: List<*>?): String {
        val picked = (tags ?: emptyList<Any?>())
            .mapNotNull { it as? String }.joinToString("\n")
        return withList(catalogue().restoreDropped(picked))
    }

    /**
     * A folder-changing answer, with the list spliced back in.
     *
     * The page hands both of these to afterPoolChange, which redraws off
     * `countries`. Without it the picker goes on showing exits that are not
     * there any more - the list memoises on a key that does not mention the
     * server set.
     */
    private fun withList(answer: String): String {
        val o = JSONObject(answer)
        if (!o.optBoolean("ok")) return answer
        reload()
        val c = catalogue()
        o.put("serverCount", c.count().toInt())
        return o.toString().dropLast(1) + ",\"countries\":${c.countriesJSON()}}"
    }

    // -- the small remembered things -------------------------------------------

    private fun favouriteSet(): MutableSet<String> =
        prefs.getStringSet("favourites", emptySet())!!.toMutableSet()

    /** The page reads `r.ok` and `r.codes`, so a bare array is a TypeError. */
    fun favourites(): String = JSONObject().apply {
        put("ok", true)
        put("codes", JSONArray(favouriteSet().toList()))
    }.toString()

    fun toggleFavourite(code: String): String {
        val set = favouriteSet()
        val on = !set.remove(code)
        if (on) set.add(code)
        prefs.edit().putStringSet("favourites", set).apply()
        // Starring changes what Starred holds, and it may empty it entirely.
        if (source() == "starred") reload()
        return JSONObject().apply {
            put("ok", true)
            put("codes", JSONArray(set.toList()))
            put("on", on)
        }.toString()
    }

    fun setSort(kind: String?): String {
        val sortBy = kind ?: "ping"
        prefs.edit().putString("sortBy", sortBy).apply()
        return JSONObject().apply {
            put("ok", true)
            put("sortBy", sortBy)
        }.toString()
    }

    fun remember(code: String): String {
        prefs.edit().putString("picked", code).apply()
        return JSONObject().put("ok", true).toString()
    }

    /**
     * The roster. The page reads `r.accounts`, so this is an object and not
     * an array - it was an array, and the account pane was always empty.
     *
     * Deferred: reading it opens the secret store, which builds a keyset
     * through the Android keystore on its first call and is slow enough to
     * be felt if it happens while the sheet is animating in.
     */
    fun accountsList(): Bridge.Later = Bridge.work {
        JSONObject().apply {
            put("ok", true)
            put("accounts", Accounts.listing(applicationContext))
        }.toString()
    }

    /**
     * Add an account, or correct one that is already there.
     *
     * Surfshark has no sign-in to do: its cluster list is public, and the
     * account is a service username and password that the exits themselves
     * check. So this is a validation and two writes. Windscribe has a login
     * and a puzzle, and is not built here yet.
     */
    fun accountAdd(provider: String?, label: String?, user: String?, password: String?): Any {
        val who = provider.orEmpty()
        val username = user.orEmpty().trim()
        val secret = password.orEmpty()

        if (who != Accounts.SURFSHARK && who != Accounts.WINDSCRIBE) {
            return refusal("Unknown provider.")
        }
        if (username.isEmpty()) return refusal("Enter the username.")
        if (secret.isEmpty()) return refusal("Enter the password.")
        if (who == Accounts.WINDSCRIBE) {
            // Windscribe has a login and a puzzle in front of it, so this
            // only fetches the puzzle. The account is written when the login
            // lands, which is a second call away.
            return Bridge.work {
                val begun = Providers.begin(username, secret)
                if (begun.optBoolean("ok")) {
                    pending = Signin(username, secret, label.orEmpty().trim())
                }
                begun.toString()
            }
        }
        // An address in this field is wrong often enough to be worth naming.
        // Surfshark issues a separate service username for manual setups, and
        // the login email is refused by every exit with the same silence as a
        // wrong password - which reads as "no server accepted just now" and
        // sends people looking at their servers folder.
        if (username.contains("@")) {
            return refusal("That looks like your login email. Surfshark issues a " +
                "separate service username for manual setups - it is on the same " +
                "page as the config files.")
        }

        return Bridge.work {
            val account = Accounts.put(applicationContext, Accounts.SURFSHARK,
                label.orEmpty().trim(), username, password = secret)
            JSONObject().apply {
                put("ok", true)
                put("id", account.optString("id"))
                put("label", account.optString("label"))
            }.toString()
        }
    }

    /** Make one the account in use for its provider. */
    fun accountUse(id: String?): Bridge.Later = Bridge.work {
        val account = Accounts.activate(applicationContext, id.orEmpty())
        if (account == null) refusal("No such account.")
        else JSONObject().apply {
            put("ok", true)
            put("label", account.optString("label"))
            put("provider", account.optString("provider"))
        }.toString()
    }

    /**
     * Remove one. The configs stay - they are files, they cost nothing, and
     * they are worth having if the account comes back.
     */
    fun accountRemove(id: String?): Bridge.Later = Bridge.work {
        val gone = Accounts.remove(applicationContext, id.orEmpty())
        if (gone == null) refusal("No such account.")
        else {
            val left = JSONArray()
            for (name in listOf(Accounts.SURFSHARK, Accounts.WINDSCRIBE)) {
                if (Accounts.credentials(applicationContext, name) != null) left.put(name)
            }
            JSONObject().apply {
                put("ok", true)
                put("label", gone.optString("label"))
                put("providers", left)
            }.toString()
        }
    }

    // -- providers -------------------------------------------------------------

    /**
     * What crossed the bridge on the way to a Windscribe login, waiting for
     * the second half of it.
     *
     * The page never sends the password back with the solved puzzle - it does
     * not have it any more - so it is held here between the two calls and
     * dropped either way when they are over.
     */
    private class Signin(val username: String, val password: String, val label: String)

    @Volatile private var pending: Signin? = null

    /**
     * Fetch Surfshark's published fleet and write what is missing.
     *
     * No sign-in involved: the cluster list is public, and the account is a
     * service credential the exits themselves check.
     */
    fun surfsharkServers(): Bridge.Later = Bridge.work {
        val got = Providers.surfshark()
        if (got.optBoolean("ok")) {
            Pin.rememberInbox(applicationContext, AppFiles.configs(applicationContext))
            reload()
        }
        got.toString()
    }

    /** The same for Windscribe, into its own inbox. */
    fun windscribeServers(freeOnly: Boolean): Bridge.Later = Bridge.work {
        val got = Providers.windscribe(freeOnly)
        if (got.optBoolean("ok")) {
            val folder = AppFiles.windscribe(applicationContext)
            Pin.rememberInbox(applicationContext, folder)
            reload()
            // The page reads this to point its pin pane at the right pile.
            got.put("pinFolder", folder.absolutePath)
        }
        got.toString()
    }

    /**
     * Half two of a Windscribe sign-in, sent the moment the puzzle is
     * released.
     *
     * One attempt. A token is spent whether or not the answer was right, and
     * asking again with the same one is how an account gets rate-limited for
     * a mistake it has already made - so the pending sign-in is dropped
     * either way.
     */
    fun windscribeFinish(
        token: String?, solution: String?, trailX: List<*>?, trailY: List<*>?,
        code2fa: String?,
    ): Bridge.Later {
        val waiting = pending
            ?: throw Bridge.NotHere("Start the sign-in again - the first half of " +
                "it has been forgotten.")
        return Bridge.work {
            try {
                val out = Providers.signIn(
                    applicationContext, waiting.username, waiting.password,
                    waiting.label, token.orEmpty(), solution.orEmpty(),
                    Providers.trail(trailX), Providers.trail(trailY),
                    code2fa.orEmpty())
                if (out.optBoolean("ok")) reload()
                out.toString()
            } finally {
                pending = null
            }
        }
    }

    /** A fresh proxy credential from the session already held. */
    fun windscribeRefresh(): Bridge.Later = Bridge.work {
        Providers.refresh(applicationContext).toString()
    }

    // -- pinning ---------------------------------------------------------------

    /** What a pin run would cost, and what would stop it. */
    fun pinPlan(): Bridge.Later = Bridge.work {
        Pin.plan(applicationContext).toString()
    }

    /**
     * One pin option at a time. `port` is taken and ignored: there is no
     * local port on a phone for anything to listen on, and the page sends the
     * argument because the desktop has one.
     */
    fun setPinRoute(route: String?, maxIPs: Int?, test: Boolean?): Bridge.Later =
        Bridge.work {
            Pin.setRoute(applicationContext, route, maxIPs, test).toString()
        }

    /** Point the inbox at one provider pile and say what is in it. */
    fun pinForProvider(provider: String?): Bridge.Later = Bridge.work {
        Pin.forProvider(applicationContext, provider.orEmpty()).toString()
    }

    /**
     * Start a run. Everything after this arrives as events, because the page
     * draws each result on the row it belongs to as it lands - and a run over
     * four hundred configs is minutes of them.
     */
    fun startPin(): String {
        val out = Pin.start(applicationContext) { payload ->
            main.post { push("Pin", payload) }
            // The folder the app reads is being written into as this runs, so
            // the list is stale from the first file onwards.
            if (payload.contains("\"phase\":\"finished\"")) {
                main.post { reload() }
            }
        }
        return out.toString()
    }

    fun cancelPin(): String = Pin.cancel().toString()

    /** The run landed in the folder the app already reads, so nothing narrows. */
    fun usePinnedFolder(): Bridge.Later = Bridge.work {
        reload()
        Pin.usePinned(applicationContext, catalogue().count().toInt()).toString()
    }

    /** Back to the account list. */
    private fun credentials() = Accounts.credentials(applicationContext, Accounts.SURFSHARK)
    private fun windscribeCredentials() =
        Accounts.credentials(applicationContext, Accounts.WINDSCRIBE)
    private fun hasAuth() = credentials() != null

    // -- the user's own server -------------------------------------------------

    /**
     * tunnelPlan(), in the shape app/main.py answers it and under the same
     * setting names, so the two halves of one account agree about what is
     * configured.
     *
     * The passwords go back as whether they are set, never as themselves -
     * the page has no use for them, and a screenshot of the settings sheet
     * should not be a leak. The desktop's rule, for the desktop's reason.
     *
     * Kept in the phone's own preferences rather than in the pinned folder or
     * in `auth`: those two are pushed at the phone from a desktop and would
     * overwrite this every time. Not in the page's localStorage either - the
     * service reads these, and the service does not have a page.
     */
    fun tunnelPlan(): String = JSONObject().apply {
        put("mode", prefs.getString("mode", "surfshark"))
        put("domain", prefs.getString("tunnelDomain", "").orEmpty())
        put("hasPassword", !prefs.getString("tunnelPassword", "").isNullOrEmpty())
        put("hasApiPassword", !prefs.getString("tunnelApiPassword", "").isNullOrEmpty())
        // The desktop asks whether gost is installed. There is nothing to
        // install here - the core speaks the tunnel itself - so the answer is
        // always yes, and the page's "get the client" prompt stays out of the
        // way.
        put("hasClient", true)
        put("running", RelayVpnService.exitAddress.isNotEmpty())
        put("edges", JSONArray(edgeList()))
    }.toString()

    private fun edgeList(): List<String> =
        prefs.getString("tunnelEdges", "").orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * saveTunnel(). Blank means unchanged, not cleared: the page never sends
     * the passwords back, so treating empty as "erase" would wipe them every
     * time the domain was edited.
     */
    fun saveTunnel(domain: String?, password: String?, apiPassword: String?): String {
        val was = prefs.getString("tunnelDomain", "")
        prefs.edit().apply {
            domain?.let { putString("tunnelDomain", it.trim()) }
            if (!password.isNullOrEmpty()) putString("tunnelPassword", password)
            if (!apiPassword.isNullOrEmpty()) putString("tunnelApiPassword", apiPassword)
            // The edges belong to the domain they were measured against.
            // Changing it has to drop them, or the next connection dials one
            // server's addresses under another server's name.
            if (domain != null && domain.trim() != was) remove("tunnelEdges")
        }.apply()
        return tunnelPlan()
    }

    /**
     * Whether the printer, the NAS, the router's own page and adb-over-wifi
     * stay reachable while the tunnel is up.
     *
     * On by default. A local address is not routable beyond the house, so
     * nothing leaves by being excluded - and a VPN that silently breaks
     * everything on the desk is a VPN that gets blamed for the wrong thing.
     *
     * Takes effect on the next connect: the routes are decided when the
     * interface is built, and Android has no way to change them under a
     * tunnel that is already up.
     */
    fun setAllowLan(on: Boolean): String {
        prefs.edit().putBoolean("allowLan", on).apply()
        return JSONObject().put("ok", true).put("allowLan", on)
            .put("needsReconnect", RelayVpnService.exitAddress.isNotEmpty())
            .toString()
    }

    /** setMode(), which the strip at the top of the window calls. */
    fun setMode(mode: String): String {
        if (mode !in setOf("surfshark", "single", "multi")) {
            return JSONObject().put("ok", false).put("error", "unknown-mode").toString()
        }
        prefs.edit().putString("mode", mode).apply()
        return JSONObject().put("ok", true).put("mode", mode).toString()
    }

    /**
     * rescanEdges(): look for a way in now, whatever state the tunnel is in.
     *
     * Deferred rather than answered at once. This is fifty-six TLS handshakes
     * and about five seconds, and the page does `const r = await
     * api.rescanEdges()` and then reads `r.edges[0]` - so an immediate
     * `{ok:true}` was a TypeError one line later, and the real answer went out
     * as an event the page has no handler for and dropped in silence.
     */
    fun rescanEdges(): Bridge.Later {
        val domain = prefs.getString("tunnelDomain", "").orEmpty()
        if (domain.isEmpty()) throw Bridge.NotHere("no server domain saved yet")

        return Bridge.work {
            val found = JSONObject(Relay.scanEdgesJSON(domain, 40000L))
            val edges = found.optJSONArray("edges") ?: JSONArray()

            val keep = StringBuilder()
            for (i in 0 until edges.length()) {
                if (i > 0) keep.append('\n')
                keep.append(edges.getString(i))
            }
            // Kept, so the next connect does not pay for it again - and only
            // when something was found, because writing an empty list over a
            // working one would turn a bad minute into a broken setting.
            if (keep.isNotEmpty()) {
                prefs.edit().putString("tunnelEdges", keep.toString()).apply()
            }

            found.put("running", RelayVpnService.exitAddress.isNotEmpty())
            found.toString()
        }
    }

    /**
     * testTunnel(): carry something through it, rather than checking a port.
     *
     * Three answers in one, the same three the desktop wants: the way in is
     * reachable, the server takes the password, and traffic actually comes
     * out the far side. The last is the one a reachability check misses.
     *
     * And one repair, once. The failure this cannot tell apart from a dead
     * server is a way in that has been filtered since it was measured - both
     * are a timeout from here - so a failure is followed by a scan, and if
     * that finds a different address the question is asked again through it.
     * Once, and then it is reported: a Test button that retries forever is a
     * Test button that never finishes.
     */
    fun testTunnel(): Bridge.Later {
        val domain = prefs.getString("tunnelDomain", "").orEmpty()
        val password = prefs.getString("tunnelPassword", "").orEmpty()
        if (domain.isEmpty() || password.isEmpty()) {
            throw Bridge.NotHere("set the domain and password first")
        }
        // surfshark is not a tunnel mode. Testing the tunnel while the strip
        // says "Provider" should still test something, and single is the
        // plainest thing to test.
        val mode = (prefs.getString("mode", "single") ?: "single").let {
            if (it == "surfshark") "single" else it
        }

        return Bridge.work {
            val cfg = Relay.newServerConfig()
            cfg.domain = domain
            cfg.password = password
            cfg.mode = mode
            cfg.edges = prefs.getString("tunnelEdges", "").orEmpty()

            val running = RelayVpnService.exitAddress.isNotEmpty()
            try {
                askThrough(cfg, domain, mode, running, JSONArray())
            } catch (first: Exception) {
                val scan = JSONObject(Relay.scanEdgesJSON(domain, 40000L))
                val found = scan.optJSONArray("edges") ?: JSONArray()
                if (found.length() == 0) {
                    JSONObject().apply {
                        put("ok", false)
                        put("verdict", "no-way-in")
                        put("restarted", true)
                        put("running", running)
                        put("error", scan.optString("error").ifEmpty {
                            first.message ?: first.toString()
                        })
                    }.toString()
                } else {
                    val keep = StringBuilder()
                    for (i in 0 until found.length()) {
                        if (i > 0) keep.append('\n')
                        keep.append(found.getString(i))
                    }
                    prefs.edit().putString("tunnelEdges", keep.toString()).apply()
                    cfg.edges = keep.toString()
                    try {
                        askThrough(cfg, domain, mode, running, found)
                    } catch (second: Exception) {
                        JSONObject().apply {
                            put("ok", false)
                            put("verdict", "still-down")
                            put("repaired", found)
                            put("restarted", true)
                            put("running", running)
                            put("error", "found a way in at ${found.getString(0)} " +
                                "and the tunnel still will not carry anything: " +
                                (second.message ?: second.toString()).take(120))
                        }.toString()
                    }
                }
            }
        }
    }

    /**
     * One question through the tunnel, and what came back.
     *
     * `seen` goes back as the address alone rather than as the object the
     * core answers with, because the page prints it into a sentence: "it
     * comes out at 164.92.225.16".
     */
    private fun askThrough(
        cfg: relay.ServerConfig, domain: String, mode: String,
        running: Boolean, repaired: JSONArray,
    ): String {
        val seen = JSONObject(Relay.testServer(cfg, 20000L))
        return JSONObject().apply {
            put("ok", true)
            put("seen", seen.optString("ip"))
            put("exit", when (mode) {
                "multi" -> "$domain (server + exit)"
                else -> "$domain (your server)"
            })
            put("restarted", false)
            put("repaired", repaired)
            put("edges", JSONArray(edgeList()))
            put("running", running)
        }.toString()
    }

    /**
     * One line that puts the installer on the server and runs it.
     *
     * The pane used to show `./install-server.sh ...`, which quietly assumed
     * the script was already on the server - and it never is. Nothing hosts
     * it, so the script travels inside the command: base64 in a single line,
     * which an SSH session takes as one paste.
     *
     * On a phone it also travels inside the APK. The desktop reads it off the
     * disk beside itself, and a phone has no disk beside itself, so
     * sync-ui.sh copies it into the assets and this reads it back out.
     *
     * The Surfshark credentials are left as placeholders rather than filled
     * in. They would otherwise sit in a clipboard and, on most machines, in a
     * shell history file on a server, to save somebody two words.
     */
    fun installCommand(domain: String?, user: String?, password: String?): String {
        val key = FlutterInjector.instance().flutterLoader()
            .getLookupKeyForAsset("assets/install-server.sh")
        val blob = try {
            assets.open(key).use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
        } catch (e: Exception) {
            return refusal("the installer is missing: ${e.message}")
        }
        val host = (domain ?: "").trim().ifEmpty { "yourdomain.com" }
        return JSONObject().apply {
            put("ok", true)
            put("command",
                "mkdir -p /opt/relay && echo '" + blob + "' | base64 -d " +
                "> /opt/relay/install-server.sh && bash " +
                "/opt/relay/install-server.sh $host " +
                (user.orEmpty().ifEmpty { "<surfshark-user>" }) + " " +
                (password.orEmpty().ifEmpty { "<surfshark-pass>" }))
        }.toString()
    }

    /**
     * The clipboard, for a page that cannot reach its own.
     *
     * `navigator.clipboard` needs a secure context and this page is loaded
     * out of the APK, so it is never available here - the page catches that
     * and falls back to this. Returning null, as this did, meant Copy on the
     * install command marked itself as copied and put nothing anywhere.
     *
     * On the main thread, which is where the bridge already runs:
     * ClipboardManager wants a Looper.
     */
    fun copy(text: String?): String {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
            ?: return refusal("This phone has no clipboard service.")
        clip.setPrimaryClip(
            android.content.ClipData.newPlainText("Relay", text.orEmpty()))
        return JSONObject().put("ok", true).toString()
    }

    /** A refusal in the shape the page reads: `if (!r.ok) say(r.error)`. */
    private fun refusal(why: String): String =
        JSONObject().put("ok", false).put("error", why).toString()

    // -- pushing ---------------------------------------------------------------

    /** One event at the page, by the name its own handler is registered under. */
    private fun push(event: String, payloadJson: String) {
        statusSink?.success(mapOf("event" to event, "payload" to payloadJson))
    }

    /**
     * onConnected, in the shape app.js reads it:
     *
     *     status.exit = { ip, host, country, seen_as: { ip, country } }
     *
     * seen_as is empty on the first push and filled by [seenAs] when the
     * check through the tunnel answers. Until then the page shows
     * "checking...", which is exactly what is true.
     */
    private fun pushConnected(seenJson: String = "{}") {
        val name = RelayVpnService.exitName
        val exit = JSONObject().apply {
            put("ip", RelayVpnService.exitAddress)
            put("host", name)
            // The country the filename claims, which is not the country the
            // traffic comes out in - the page shows both and says so when
            // they differ.
            put("country", name.take(2))
            put("seen_as", JSONObject(seenJson))
        }
        push("Connected", JSONObject().apply { put("exit", exit) }.toString())
    }

    private fun pushStatus() {
        if (statusSink == null) return
        val up = RelayVpnService.exitAddress.isNotEmpty()
        when {
            RelayVpnService.status.startsWith("failed") ->
                push("Failed", JSONObject().put(
                    "reason", RelayVpnService.status.removePrefix("failed: ")).toString())

            up -> pushConnected()

            RelayVpnService.total > 0 -> push("Progress", JSONObject().apply {
                put("phase", "probing")
                put("asked", RelayVpnService.asked)
                put("total", RelayVpnService.total)
            }.toString())

            else -> push("Disconnected", "null")
        }
    }
}
