package com.erelay.relay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import relay.Relay
import java.io.File

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
        private const val PREFS = "relay"
    }

    private var statusSink: EventChannel.EventSink? = null
    private var pendingConnect: MethodChannel.Result? = null
    private var pendingCountry = "auto"

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = pushStatus()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONTROL)
            .setMethodCallHandler { call, result ->
                if (call.method != "call") {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
                val name = call.argument<String>("name").orEmpty()
                val args = call.argument<List<Any?>>("args") ?: emptyList()
                try {
                    result.success(Bridge.call(this, name, args))
                } catch (e: Bridge.NotHere) {
                    result.error("not-here", e.message, null)
                } catch (e: Exception) {
                    result.error("failed", e.message ?: e.toString(), null)
                }
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

    private val meter = android.os.Handler(android.os.Looper.getMainLooper())
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

    // -- where the files are ---------------------------------------------------

    private fun pinnedDir() = File(getExternalFilesDir(null), "pinned")
    private fun authFile() = File(getExternalFilesDir(null), "auth")

    private fun configCount() =
        pinnedDir().listFiles { f -> f.name.endsWith(".ovpn") }?.size ?: 0

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
        val countries = try {
            Relay.newClient("", "").let { c ->
                c.scanFolder(pinnedDir().absolutePath)
                c.countriesJSON()
            }
        } catch (e: Exception) {
            "[]"
        }

        val o = JSONObject()
        o.put("serverCount", configCount())
        o.put("folder", pinnedDir().absolutePath)
        o.put("folders", JSONArray().put(pinnedDir().absolutePath))
        o.put("providers", JSONArray().put("surfshark").put("windscribe"))
        o.put("providerState", JSONObject().apply {
            put("surfshark", JSONObject().apply {
                put("servers", configCount()); put("account", hasAuth()); put("usable", hasAuth())
            })
            put("windscribe", JSONObject().apply {
                put("servers", 0); put("account", false); put("usable", false)
            })
        })
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

        // Spliced rather than parsed: it is already the JSON the page wants,
        // and decoding it here only to encode it again would be two parses of
        // a hundred kilobytes for no change to a single byte.
        return o.toString().dropLast(1) + ",\"countries\":$countries}"
    }

    fun statusMap(): String = JSONObject().apply {
        val up = RelayVpnService.exitAddress.isNotEmpty()
        put("state", if (up) "connected" else if (RelayVpnService.total > 0) "working" else "idle")
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
            val json = Relay.whereAmIJSON(8000)
            runOnUiThread { push("RealIp", json) }
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
        val creds = credentials()
        if (addr.isEmpty() || creds == null) return "{}"

        Thread {
            val seen = Relay.seenAsJSON(addr, name, creds.first, creds.second, 15000)
            runOnUiThread { pushConnected(seen) }
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

    fun exitsIn(country: String): String {
        return try {
            val c = Relay.newClient("", "")
            c.scanFolder(pinnedDir().absolutePath)
            c.countriesJSON()
        } catch (e: Exception) {
            "[]"
        }
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
        startService(country)
        return """{"ok":true}"""
    }

    @Deprecated("startActivityForResult, kept because VpnService.prepare uses it")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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
            val creds = credentials() ?: throw Bridge.NotHere(
                "no credentials. ${authFile().absolutePath} wants a username on " +
                    "one line and a password on the next"
            )
            intent
                .putExtra(RelayVpnService.EXTRA_PROVIDER, "surfshark")
                .putExtra(RelayVpnService.EXTRA_USER, creds.first)
                .putExtra(RelayVpnService.EXTRA_PASSWORD, creds.second)
                .putExtra(RelayVpnService.EXTRA_CONFIG_DIR, pinnedDir().absolutePath)
                .putExtra(RelayVpnService.EXTRA_COUNTRY, country)
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

    // -- the small remembered things -------------------------------------------

    private fun favouriteSet(): MutableSet<String> =
        prefs.getStringSet("favourites", emptySet())!!.toMutableSet()

    fun favourites(): String = JSONArray(favouriteSet().toList()).toString()

    fun toggleFavourite(code: String): String {
        val set = favouriteSet()
        if (!set.remove(code)) set.add(code)
        prefs.edit().putStringSet("favourites", set).apply()
        return favourites()
    }

    fun setSort(kind: String?): String? {
        prefs.edit().putString("sortBy", kind ?: "ping").apply()
        return null
    }

    fun remember(code: String): String? {
        prefs.edit().putString("picked", code).apply()
        return null
    }

    fun accountsList(): String = JSONArray().apply {
        credentials()?.let { (user, _) ->
            put(JSONObject().apply {
                put("id", "surfshark")
                put("provider", "surfshark")
                put("label", user)
                put("username", user)
                put("signedIn", true)
                put("active", true)
            })
        }
    }.toString()

    private fun credentials() = Bridge.credentials(authFile())
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
     * Off the main thread - fifty-six TLS handshakes, about five seconds -
     * and the answer arrives as an event rather than as a return, because the
     * page redraws the settings pane from a fresh plan either way.
     */
    fun rescanEdges(): String {
        val domain = prefs.getString("tunnelDomain", "").orEmpty()
        if (domain.isEmpty()) throw Bridge.NotHere("no server domain saved yet")

        Thread {
            val found = try {
                Relay.scanEdges(domain, 40000)
            } catch (e: Exception) {
                // Not FlutterActivity's TAG, which is private and shadows
                // ours from inside this class.
                Log.w(RelayVpnService.TAG, "edge scan: ${e.message}")
                ""
            }
            prefs.edit().putString("tunnelEdges", found).apply()
            runOnUiThread { push("Tunnel", tunnelPlan()) }
        }.start()
        return JSONObject().put("ok", true).toString()
    }

    /**
     * testTunnel(): carry something through it, rather than checking a port.
     *
     * Three answers in one, the same three the desktop wants: the way in is
     * reachable, the server takes the password, and traffic actually comes
     * out the far side. The last is the one a reachability check misses.
     */
    fun testTunnel(): String {
        val domain = prefs.getString("tunnelDomain", "").orEmpty()
        val password = prefs.getString("tunnelPassword", "").orEmpty()
        // surfshark is not a tunnel mode. Testing the tunnel while the strip
        // says "Provider" should still test something, and single is the
        // plainest thing to test.
        val mode = prefs.getString("mode", "single").let {
            if (it == "surfshark") "single" else it
        }
        if (domain.isEmpty() || password.isEmpty()) {
            throw Bridge.NotHere("set the domain and password first")
        }

        Thread {
            val cfg = Relay.newServerConfig()
            cfg.domain = domain
            cfg.password = password
            cfg.mode = mode
            cfg.edges = prefs.getString("tunnelEdges", "").orEmpty()

            val out = JSONObject()
            try {
                out.put("ok", true).put("seen", JSONObject(Relay.testServer(cfg, 20000)))
            } catch (e: Exception) {
                out.put("ok", false).put("error", e.message ?: e.toString())
            }
            runOnUiThread { push("Tunnel", out.toString()) }
        }.start()
        return JSONObject().put("ok", true).put("testing", true).toString()
    }

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
